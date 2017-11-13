package com.fasterxml.jackson.core.filter;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.sym.FieldNameMatcher;
import com.fasterxml.jackson.core.util.JsonParserDelegate;

import static com.fasterxml.jackson.core.JsonTokenId.*;

/**
 * Specialized {@link JsonParserDelegate} that allows use of
 * {@link TokenFilter} for outputting a subset of content that
 * is visible to caller
 */
public class FilteringParserDelegate extends JsonParserDelegate
{
    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */
    
    /**
     * Object consulted to determine whether to write parts of content generator
     * is asked to write or not.
     */
    protected TokenFilter rootFilter;

    /**
     * Flag that determines whether filtering will continue after the first
     * match is indicated or not: if `false`, output is based on just the first
     * full match (returning {@link TokenFilter#INCLUDE_ALL}) and no more
     * checks are made; if `true` then filtering will be applied as necessary
     * until end of content.
     */
    protected boolean _allowMultipleMatches;

    /**
     * Flag that determines whether path leading up to included content should
     * also be automatically included or not. If `false`, no path inclusion is
     * done and only explicitly included entries are output; if `true` then
     * path from main level down to match is also included as necessary.
     */
    protected boolean _includePath;

    /*
    /**********************************************************
    /* State
    /**********************************************************
     */

    /**
     * Last token retrieved via {@link #nextToken}, if any.
     * Null before the first call to <code>nextToken()</code>,
     * as well as if token has been explicitly cleared
     */
    protected JsonToken _currToken;

    /**
     * Last cleared token, if any: that is, value that was in
     * effect when {@link #clearCurrentToken} was called.
     */
    protected JsonToken _lastClearedToken;
    
    /**
     * During traversal this is the actual "open" parse tree, which sometimes
     * is the same as {@link #_exposedContext}, and at other times is ahead
     * of it. Note that this context is never null.
     */
    protected TokenFilterContext _headContext;

    /**
     * In cases where {@link #_headContext} is "ahead" of context exposed to
     * caller, this context points to what is currently exposed to caller.
     * When the two are in sync, this context reference will be <code>null</code>.
     */
    protected TokenFilterContext _exposedContext;

    /**
     * State that applies to the item within container, used where applicable.
     * Specifically used to pass inclusion state between property name and
     * property, and also used for array elements.
     */
    protected TokenFilter _itemFilter;
    
    /**
     * Number of tokens for which {@link TokenFilter#INCLUDE_ALL}
     * has been returned.
     */
    protected int _matchCount;

    /*
    /**********************************************************
    /* Construction, initialization
    /**********************************************************
     */

    public FilteringParserDelegate(JsonParser p, TokenFilter f,
            boolean includePath, boolean allowMultipleMatches)
    {
        super(p);
        rootFilter = f;
        // and this is the currently active filter for root values
        _itemFilter = f;
        _headContext = TokenFilterContext.createRootContext(f);
        _includePath = includePath;
        _allowMultipleMatches = allowMultipleMatches;
    }

    /*
    /**********************************************************
    /* Extended API
    /**********************************************************
     */

    public TokenFilter getFilter() { return rootFilter; }

    /**
     * Accessor for finding number of matches, where specific token and sub-tree
     * starting (if structured type) are passed.
     */
    public int getMatchCount() {
        return _matchCount;
    }

    /*
    /**********************************************************
    /* Public API, token accessors
    /**********************************************************
     */

    @Override public JsonToken currentToken() { return _currToken; }
    @Override public final int currentTokenId() {
        final JsonToken t = _currToken;
        return (t == null) ? JsonTokenId.ID_NO_TOKEN : t.id();
    }

    @Override public boolean hasCurrentToken() { return _currToken != null; }
    @Override public boolean hasTokenId(int id) {
        final JsonToken t = _currToken;
        if (t == null) {
            return (JsonTokenId.ID_NO_TOKEN == id);
        }
        return t.id() == id;
    }

    @Override public final boolean hasToken(JsonToken t) {
        return (_currToken == t);
    }
    
    @Override public boolean isExpectedStartArrayToken() { return _currToken == JsonToken.START_ARRAY; }
    @Override public boolean isExpectedStartObjectToken() { return _currToken == JsonToken.START_OBJECT; }

    @Override public JsonLocation getCurrentLocation() { return delegate.getCurrentLocation(); }

    @Override
    public TokenStreamContext getParsingContext() {
        return _filterContext();
    }
    
    // !!! TODO: Verify it works as expected: copied from standard JSON parser impl
    @Override
    public String getCurrentName() throws IOException {
        TokenStreamContext ctxt = _filterContext();
        if (_currToken == JsonToken.START_OBJECT || _currToken == JsonToken.START_ARRAY) {
            TokenStreamContext parent = ctxt.getParent();
            return (parent == null) ? null : parent.getCurrentName();
        }
        return ctxt.getCurrentName();
    }

    /*
    /**********************************************************
    /* Public API, token state overrides
    /**********************************************************
     */

    @Override
    public void clearCurrentToken() {
        if (_currToken != null) {
            _lastClearedToken = _currToken;
            _currToken = null;
        }
    }

    @Override
    public JsonToken getLastClearedToken() { return _lastClearedToken; }

    @Override
    public void overrideCurrentName(String name) {
        /* 14-Apr-2015, tatu: Not sure whether this can be supported, and if so,
         *    what to do with it... Delegation won't work for sure, so let's for
         *    now throw an exception
         */
        throw new UnsupportedOperationException("Can not currently override name during filtering read");
    }

    /*
    /**********************************************************
    /* Public API, traversal
    /**********************************************************
     */

    @Override
    public JsonToken nextToken() throws IOException
    {
        // 23-May-2017, tatu: To be honest, code here is rather hairy and I don't like all
        //    conditionals; and it seems odd to return `null` but NOT considering input
        //    as closed... would love a rewrite to simplify/clear up logic here.
        
        // Check for _allowMultipleMatches - false and at least there is one token - which is _currToken
        // check for no buffered context _exposedContext - null
        // If all the conditions matches then check for scalar / non-scalar property

        if (!_allowMultipleMatches && (_currToken != null) && (_exposedContext == null)) {
            // if scalar, and scalar not present in obj/array and !includePath and INCLUDE_ALL
            // matched once, return null
            if (_currToken.isScalarValue() && !_headContext.isStartHandled() && !_includePath
                    && (_itemFilter == TokenFilter.INCLUDE_ALL)) {
                return (_currToken = null);
            }
        }
        // Anything buffered?
        TokenFilterContext ctxt = _exposedContext;

        if (ctxt != null) {
            while (true) {
                JsonToken t = ctxt.nextTokenToRead();
                if (t != null) {
                    _currToken = t;
                    return t;
                }
                // all done with buffered stuff?
                if (ctxt == _headContext) {
                    _exposedContext = null;
                    if (ctxt.inArray()) {
                        t = delegate.currentToken();
// Is this guaranteed to work without further checks?
//                        if (t != JsonToken.START_ARRAY) {
                        _currToken = t;
                        return t;
                    }

                    // Almost! Most likely still have the current token;
                    // with the sole exception of 
                    /*
                    t = delegate.getCurrentToken();
                    if (t != JsonToken.FIELD_NAME) {
                        _currToken = t;
                        return t;
                    }
                    */
                    break;
                }
                // If not, traverse down the context chain
                ctxt = _headContext.findChildOf(ctxt);
                _exposedContext = ctxt;
                if (ctxt == null) { // should never occur
                    throw _constructError("Unexpected problem: chain of filtered context broken");
                }
            }
        }

        // If not, need to read more. If we got any:
        JsonToken t = delegate.nextToken();
        if (t == null) {
            // no strict need to close, since we have no state here
            _currToken = t;
            return t;
        }

        // otherwise... to include or not?
        TokenFilter f;
        
        switch (t.id()) {
        case ID_START_ARRAY:
            f = _itemFilter;
            if (f == TokenFilter.INCLUDE_ALL) {
                _headContext = _headContext.createChildArrayContext(f, true);
                return (_currToken = t);
            }
            if (f == null) { // does this occur?
                delegate.skipChildren();
                break;
            }
            // Otherwise still iffy, need to check
            f = _headContext.checkValue(f);
            if (f == null) {
                delegate.skipChildren();
                break;
            }
            if (f != TokenFilter.INCLUDE_ALL) {
                f = f.filterStartArray();
            }
            _itemFilter = f;
            if (f == TokenFilter.INCLUDE_ALL) {
                _headContext = _headContext.createChildArrayContext(f, true);
                return (_currToken = t);
            }
            _headContext = _headContext.createChildArrayContext(f, false);
            
            // Also: only need buffering if parent path to be included
            if (_includePath) {
                t = _nextTokenWithBuffering(_headContext);
                if (t != null) {
                    _currToken = t;
                    return t;
                }
            }
            break;

        case ID_START_OBJECT:
            f = _itemFilter;
            if (f == TokenFilter.INCLUDE_ALL) {
                _headContext = _headContext.createChildObjectContext(f, true);
                return (_currToken = t);
            }
            if (f == null) { // does this occur?
                delegate.skipChildren();
                break;
            }
            // Otherwise still iffy, need to check
            f = _headContext.checkValue(f);
            if (f == null) {
                delegate.skipChildren();
                break;
            }
            if (f != TokenFilter.INCLUDE_ALL) {
                f = f.filterStartObject();
            }
            _itemFilter = f;
            if (f == TokenFilter.INCLUDE_ALL) {
                _headContext = _headContext.createChildObjectContext(f, true);
                return (_currToken = t);
            }
            _headContext = _headContext.createChildObjectContext(f, false);
            // Also: only need buffering if parent path to be included
            if (_includePath) {
                t = _nextTokenWithBuffering(_headContext);
                if (t != null) {
                    _currToken = t;
                    return t;
                }
            }
            // note: inclusion of surrounding Object handled separately via
            // FIELD_NAME
            break;

        case ID_END_ARRAY:
        case ID_END_OBJECT:
            {
                boolean returnEnd = _headContext.isStartHandled();
                f = _headContext.getFilter();
                if ((f != null) && (f != TokenFilter.INCLUDE_ALL)) {
                    f.filterFinishArray();
                }
                _headContext = _headContext.getParent();
                _itemFilter = _headContext.getFilter();
                if (returnEnd) {
                    return (_currToken = t);
                }
            }
            break;

        case ID_FIELD_NAME:
            {
                final String name = delegate.getCurrentName();
                // note: this will also set 'needToHandleName'
                f = _headContext.setFieldName(name);
                if (f == TokenFilter.INCLUDE_ALL) {
                    _itemFilter = f;
                    return (_currToken = t);
                }
                if (f == null) {
                    delegate.nextToken();
                    delegate.skipChildren();
                    break;
                }
                f = f.includeProperty(name);
                if (f == null) {
                    delegate.nextToken();
                    delegate.skipChildren();
                    break;
                }
                _itemFilter = f;
                if (f == TokenFilter.INCLUDE_ALL) {
                    if (_verifyAllowedMatches()) {
                        if (_includePath) {
                            return (_currToken = t);
                        }
                    } else {
                        delegate.nextToken();
                        delegate.skipChildren();
                    }
                }
                if (_includePath) {
                    t = _nextTokenWithBuffering(_headContext);
                    if (t != null) {
                        _currToken = t;
                        return t;
                    }
                }
                break;
            }

        default: // scalar value
            f = _itemFilter;
            if (f == TokenFilter.INCLUDE_ALL) {
                return (_currToken = t);
            }
            if (f != null) {
                f = _headContext.checkValue(f);
                if ((f == TokenFilter.INCLUDE_ALL)
                        || ((f != null) && f.includeValue(delegate))) {
                    if (_verifyAllowedMatches()) {
                        return (_currToken = t);
                    }
                }
            }
            // Otherwise not included (leaves must be explicitly included)
            break;
        }

        // We get here if token was not yet found; offlined handling
        return _nextToken2();
    }

    /**
     * Offlined handling for cases where there was no buffered token to
     * return, and the token read next could not be returned as-is,
     * at least not yet, but where we have not yet established that
     * buffering is needed.
     */
    protected final JsonToken _nextToken2() throws IOException
    {
        main_loop:
        while (true) {
            JsonToken t = delegate.nextToken();
            if (t == null) { // is this even legal?
                _currToken = t;
                return t;
            }
            TokenFilter f;

            switch (t.id()) {
            case ID_START_ARRAY:
                f = _itemFilter;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildArrayContext(f, true);
                    return (_currToken = t);
                }
                if (f == null) { // does this occur?
                    delegate.skipChildren();
                    continue main_loop;
                }
                // Otherwise still iffy, need to check
                f = _headContext.checkValue(f);
                if (f == null) {
                    delegate.skipChildren();
                    continue main_loop;
                }
                if (f != TokenFilter.INCLUDE_ALL) {
                    f = f.filterStartArray();
                }
                _itemFilter = f;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildArrayContext(f, true);
                    return (_currToken = t);
                }
                _headContext = _headContext.createChildArrayContext(f, false);
                // but if we didn't figure it out yet, need to buffer possible events
                if (_includePath) {
                    t = _nextTokenWithBuffering(_headContext);
                    if (t != null) {
                        _currToken = t;
                        return t;
                    }
                }
                continue main_loop;

            case ID_START_OBJECT:
                f = _itemFilter;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildObjectContext(f, true);
                    return (_currToken = t);
                }
                if (f == null) { // does this occur?
                    delegate.skipChildren();
                    continue main_loop;
                }
                // Otherwise still iffy, need to check
                f = _headContext.checkValue(f);
                if (f == null) {
                    delegate.skipChildren();
                    continue main_loop;
                }
                if (f != TokenFilter.INCLUDE_ALL) {
                    f = f.filterStartObject();
                }
                _itemFilter = f;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildObjectContext(f, true);
                    return (_currToken = t);
                }
                _headContext = _headContext.createChildObjectContext(f, false);
                if (_includePath) {
                    t = _nextTokenWithBuffering(_headContext);
                    if (t != null) {
                        _currToken = t;
                        return t;
                    }
                }
                continue main_loop;

            case ID_END_ARRAY:
            case ID_END_OBJECT:
                {
                    boolean returnEnd = _headContext.isStartHandled();
                    f = _headContext.getFilter();
                    if ((f != null) && (f != TokenFilter.INCLUDE_ALL)) {
                        f.filterFinishArray();
                    }
                    _headContext = _headContext.getParent();
                    _itemFilter = _headContext.getFilter();
                    if (returnEnd) {
                        return (_currToken = t);
                    }
                }
                continue main_loop;

            case ID_FIELD_NAME:
                {
                    final String name = delegate.getCurrentName();
                    f = _headContext.setFieldName(name);
                    if (f == TokenFilter.INCLUDE_ALL) {
                        _itemFilter = f;
                        return (_currToken = t);
                    }
                    if (f == null) { // filter out the value
                        delegate.nextToken();
                        delegate.skipChildren();
                        continue main_loop;
                    }
                    f = f.includeProperty(name);
                    if (f == null) { // filter out the value
                        delegate.nextToken();
                        delegate.skipChildren();
                        continue main_loop;
                    }
                    _itemFilter = f;
                    if (f == TokenFilter.INCLUDE_ALL) {
                        if (_verifyAllowedMatches() && _includePath) {
                            return (_currToken = t);
                        }
                        continue main_loop;
                    }
                    if (_includePath) {
                        t = _nextTokenWithBuffering(_headContext);
                        if (t != null) {
                            _currToken = t;
                            return t;
                        }
                    }
                }
                continue main_loop;

            default: // scalar value
                f = _itemFilter;
                if (f == TokenFilter.INCLUDE_ALL) {
                    return (_currToken = t);
                }
                if (f != null) {
                    f = _headContext.checkValue(f);
                    if ((f == TokenFilter.INCLUDE_ALL)
                            || ((f != null) && f.includeValue(delegate))) {
                        if (_verifyAllowedMatches()) {
                            return (_currToken = t);
                        }
                    }
                }
                // Otherwise not included (leaves must be explicitly included)
                break;
            }
        }
    }

    /**
     * Method called when a new potentially included context is found.
     */
    protected final JsonToken _nextTokenWithBuffering(final TokenFilterContext buffRoot)
        throws IOException
    {
        main_loop:
        while (true) {
            JsonToken t = delegate.nextToken();
            if (t == null) { // is this even legal?
                return t;
            }
            TokenFilter f;

            // One simplification here: we know for a fact that the item filter is
            // neither null nor 'include all', for most cases; the only exception
            // being FIELD_NAME handling

            switch (t.id()) {
            case ID_START_ARRAY:
                f = _headContext.checkValue(_itemFilter);
                if (f == null) {
                    delegate.skipChildren();
                    continue main_loop;
                }
                if (f != TokenFilter.INCLUDE_ALL) {
                    f = f.filterStartArray();
                }
                _itemFilter = f;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildArrayContext(f, true);
                    return _nextBuffered(buffRoot);
                }
                _headContext = _headContext.createChildArrayContext(f, false);
                continue main_loop;

            case ID_START_OBJECT:
                f = _itemFilter;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildObjectContext(f, true);
                    return t;
                }
                if (f == null) { // does this occur?
                    delegate.skipChildren();
                    continue main_loop;
                }
                // Otherwise still iffy, need to check
                f = _headContext.checkValue(f);
                if (f == null) {
                    delegate.skipChildren();
                    continue main_loop;
                }
                if (f != TokenFilter.INCLUDE_ALL) {
                    f = f.filterStartObject();
                }
                _itemFilter = f;
                if (f == TokenFilter.INCLUDE_ALL) {
                    _headContext = _headContext.createChildObjectContext(f, true);
                    return _nextBuffered(buffRoot);
                }
                _headContext = _headContext.createChildObjectContext(f, false);
                continue main_loop;

            case ID_END_ARRAY:
            case ID_END_OBJECT:
                {
                    // Unlike with other loops, here we know that content was NOT
                    // included (won't get this far otherwise)
                    f = _headContext.getFilter();
                    if ((f != null) && (f != TokenFilter.INCLUDE_ALL)) {
                        f.filterFinishArray();
                    }
                    boolean gotEnd = (_headContext == buffRoot);
                    boolean returnEnd = gotEnd && _headContext.isStartHandled();

                    _headContext = _headContext.getParent();
                    _itemFilter = _headContext.getFilter();

                    if (returnEnd) {
                        return t;
                    }
                }
                continue main_loop;

            case ID_FIELD_NAME:
                {
                    final String name = delegate.getCurrentName();
                    f = _headContext.setFieldName(name);
                    if (f == TokenFilter.INCLUDE_ALL) {
                        _itemFilter = f;
                        return _nextBuffered(buffRoot);
                    }
                    if (f == null) { // filter out the value
                        delegate.nextToken();
                        delegate.skipChildren();
                        continue main_loop;
                    }
                    f = f.includeProperty(name);
                    if (f == null) { // filter out the value
                        delegate.nextToken();
                        delegate.skipChildren();
                        continue main_loop;
                    }
                    _itemFilter = f;
                    if (f == TokenFilter.INCLUDE_ALL) {
                        if (_verifyAllowedMatches()) {
                            return _nextBuffered(buffRoot);
                        } else {
                            // edge case: if no more matches allowed, reset filter
                            // to initial state to prevent missing a token in next iteration
                            _itemFilter = _headContext.setFieldName(name);
                        }
                    }
                }
                continue main_loop;

            default: // scalar value
                f = _itemFilter;
                if (f == TokenFilter.INCLUDE_ALL) {
                    return _nextBuffered(buffRoot);
                }
                if (f != null) {
                    f = _headContext.checkValue(f);
                    if ((f == TokenFilter.INCLUDE_ALL)
                            || ((f != null) && f.includeValue(delegate))) {
                        if (_verifyAllowedMatches()) {
                            return _nextBuffered(buffRoot);
                        }
                    }
                }
                // Otherwise not included (leaves must be explicitly included)
                continue main_loop;
            }
        }
    }

    private JsonToken _nextBuffered(TokenFilterContext buffRoot) throws IOException
    {
        _exposedContext = buffRoot;
        TokenFilterContext ctxt = buffRoot;
        JsonToken t = ctxt.nextTokenToRead();
        if (t != null) {
            return t;
        }
        while (true) {
            // all done with buffered stuff?
            if (ctxt == _headContext) {
                throw _constructError("Internal error: failed to locate expected buffered tokens");
                /*
                _exposedContext = null;
                break;
                */
            }
            // If not, traverse down the context chain
            ctxt = _exposedContext.findChildOf(ctxt);
            _exposedContext = ctxt;
            if (ctxt == null) { // should never occur
                throw _constructError("Unexpected problem: chain of filtered context broken");
            }
            t = _exposedContext.nextTokenToRead();
            if (t != null) {
                return t;
            }
        }
    }

    private final boolean _verifyAllowedMatches() throws IOException {
        if (_matchCount == 0 || _allowMultipleMatches) {
            ++_matchCount;
            return true;
        }
        return false;
    }

    @Override
    public JsonToken nextValue() throws IOException {
        // Re-implemented same as ParserMinimalBase:
        JsonToken t = nextToken();
        if (t == JsonToken.FIELD_NAME) {
            t = nextToken();
        }
        return t;
    }

    /**
     * Need to override, re-implement similar to how method defined in
     * {@link com.fasterxml.jackson.core.base.ParserMinimalBase}, to keep
     * state correct here.
     */
    @Override
    public JsonParser skipChildren() throws IOException
    {
        if ((_currToken != JsonToken.START_OBJECT)
            && (_currToken != JsonToken.START_ARRAY)) {
            return this;
        }
        int open = 1;

        // Since proper matching of start/end markers is handled
        // by nextToken(), we'll just count nesting levels here
        while (true) {
            JsonToken t = nextToken();
            if (t == null) { // not ideal but for now, just return
                return this;
            }
            if (t.isStructStart()) {
                ++open;
            } else if (t.isStructEnd()) {
                if (--open == 0) {
                    return this;
                }
            }
        }
    }

    /*
    /**********************************************************
    /* Public API, traversal methods that CAN NOT just delegate
    /* and where we need to override default delegation
    /**********************************************************
     */

    @Override
    public String nextFieldName() throws IOException {
        return (nextToken() == JsonToken.FIELD_NAME) ? getCurrentName() : null;
    }

    @Override
    public boolean nextFieldName(SerializableString str) throws IOException {
        return (nextToken() == JsonToken.FIELD_NAME) && str.getValue().equals(getCurrentName());
    }

    @Override
    public int nextFieldName(FieldNameMatcher matcher) throws IOException {
        String str = nextFieldName();
        if (str != null) {
            return matcher.matchName(str);
        }
        if (hasToken(JsonToken.END_OBJECT)) {
            return FieldNameMatcher.MATCH_END_OBJECT;
        }
        return FieldNameMatcher.MATCH_ODD_TOKEN;
    }

    /*
    /**********************************************************
    /* Internal helper methods
    /**********************************************************
     */

    protected TokenStreamContext _filterContext() {
        if (_exposedContext != null) {
            return _exposedContext;
        }
        return _headContext;
    }
}
