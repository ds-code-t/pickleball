//package tools.dscode.common.mappings.custommappings;
//
//
//import java.io.*;
//import java.lang.reflect.Type;
//import java.net.URL;
//import java.security.AccessController;
//import java.security.PrivilegedAction;
//import java.text.DateFormat;
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.atomic.AtomicReference;
//import java.util.function.Consumer;
//
//import com.fasterxml.jackson.annotation.*;
//
//import com.fasterxml.jackson.core.*;
//import com.fasterxml.jackson.core.exc.StreamReadException;
//import com.fasterxml.jackson.core.exc.StreamWriteException;
//import com.fasterxml.jackson.core.io.CharacterEscapes;
//import com.fasterxml.jackson.core.io.SegmentedStringWriter;
//import com.fasterxml.jackson.core.type.ResolvedType;
//import com.fasterxml.jackson.core.type.TypeReference;
//import com.fasterxml.jackson.core.util.*;
//import com.fasterxml.jackson.databind.AbstractTypeResolver;
//import com.fasterxml.jackson.databind.AnnotationIntrospector;
//import com.fasterxml.jackson.databind.DatabindException;
//import com.fasterxml.jackson.databind.DeserializationConfig;
//import com.fasterxml.jackson.databind.DeserializationContext;
//import com.fasterxml.jackson.databind.DeserializationFeature;
//import com.fasterxml.jackson.databind.EnumNamingStrategy;
//import com.fasterxml.jackson.databind.InjectableValues;
//import com.fasterxml.jackson.databind.JavaType;
//import com.fasterxml.jackson.databind.JsonDeserializer;
//import com.fasterxml.jackson.databind.JsonMappingException;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.JsonSerializer;
//import com.fasterxml.jackson.databind.MapperFeature;
//import com.fasterxml.jackson.databind.MappingIterator;
//import com.fasterxml.jackson.databind.MappingJsonFactory;
//import com.fasterxml.jackson.databind.Module;
//import com.fasterxml.jackson.databind.ObjectReader;
//import com.fasterxml.jackson.databind.ObjectWriter;
//import com.fasterxml.jackson.databind.PropertyNamingStrategy;
//import com.fasterxml.jackson.databind.SerializationConfig;
//import com.fasterxml.jackson.databind.SerializationFeature;
//import com.fasterxml.jackson.databind.SerializerProvider;
//import com.fasterxml.jackson.databind.cfg.*;
//import com.fasterxml.jackson.databind.deser.*;
//import com.fasterxml.jackson.databind.exc.MismatchedInputException;
//import com.fasterxml.jackson.databind.introspect.*;
//import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
//import com.fasterxml.jackson.databind.jsontype.*;
//import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
//import com.fasterxml.jackson.databind.jsontype.impl.StdSubtypeResolver;
//import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder;
//import com.fasterxml.jackson.databind.node.*;
//import com.fasterxml.jackson.databind.ser.*;
//import com.fasterxml.jackson.databind.type.*;
//import com.fasterxml.jackson.databind.util.ClassUtil;
//import com.fasterxml.jackson.databind.util.RootNameLookup;
//import com.fasterxml.jackson.databind.util.StdDateFormat;
//import com.fasterxml.jackson.databind.util.TokenBuffer;
//import io.cucumber.java.en.But;
//
//
//public class ObjectMapper
//        extends ObjectCodec
//        implements Versioned,
//        java.io.Serializable // as of 2.1
//{
//
//
//
//
//    @Override
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(JsonParser p, Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("p", p);
//        return (T) _readValue(getDeserializationConfig(), p, _typeFactory.constructType(valueType));
//    }
//
//
//    @Override
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(JsonParser p, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("p", p);
//        return (T) _readValue(getDeserializationConfig(), p, _typeFactory.constructType(valueTypeRef));
//    }
//
//
//    @Override
//    @SuppressWarnings("unchecked")
//    public final <T> T readValue(JsonParser p, ResolvedType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("p", p);
//        return (T) _readValue(getDeserializationConfig(), p, (JavaType) valueType);
//    }
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(JsonParser p, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("p", p);
//        return (T) _readValue(getDeserializationConfig(), p, valueType);
//    }
//
//
//    @Override
//    public <T extends TreeNode> T readTree(JsonParser p)
//            throws IOException
//    {
//        _assertNotNull("p", p);
//        // Must check for EOF here before calling readValue(), since that'll choke on it otherwise
//        DeserializationConfig cfg = getDeserializationConfig();
//        JsonToken t = p.currentToken();
//        if (t == null) {
//            t = p.nextToken();
//            if (t == null) {
//                return null;
//            }
//        }
//        // NOTE! _readValue() will check for trailing tokens
//        JsonNode n = (JsonNode) _readValue(cfg, p, constructType(JsonNode.class));
//        if (n == null) {
//            n = getNodeFactory().nullNode();
//        }
//        @SuppressWarnings("unchecked")
//        T result = (T) n;
//        return result;
//    }
//
//
//    @Override
//    public <T> MappingIterator<T> readValues(JsonParser p, ResolvedType valueType)
//            throws IOException
//    {
//        return readValues(p, (JavaType) valueType);
//    }
//
//
//    public <T> MappingIterator<T> readValues(JsonParser p, JavaType valueType)
//            throws IOException
//    {
//        _assertNotNull("p", p);
//        DeserializationConfig config = getDeserializationConfig();
//        DeserializationContext ctxt = createDeserializationContext(p, config);
//        JsonDeserializer<?> deser = _findRootDeserializer(ctxt, valueType);
//        // false -> do NOT close JsonParser (since caller passed it)
//        return new MappingIterator<T>(valueType, p, ctxt, deser,
//                false, null);
//    }
//
//
//    @Override
//    public <T> MappingIterator<T> readValues(JsonParser p, Class<T> valueType)
//            throws IOException
//    {
//        return readValues(p, _typeFactory.constructType(valueType));
//    }
//
//
//    @Override
//    public <T> MappingIterator<T> readValues(JsonParser p, TypeReference<T> valueTypeRef)
//            throws IOException
//    {
//        return readValues(p, _typeFactory.constructType(valueTypeRef));
//    }
//
//
//
//
//
//    public JsonNode readTree(InputStream in) throws IOException
//    {
//        _assertNotNull("in", in);
//        return _readTreeAndClose(_jsonFactory.createParser(in));
//    }
//
//
//    public JsonNode readTree(Reader r) throws IOException {
//        _assertNotNull("r", r);
//        return _readTreeAndClose(_jsonFactory.createParser(r));
//    }
//
//
//    public JsonNode readTree(String content) throws JsonProcessingException, JsonMappingException
//    {
//        _assertNotNull("content", content);
//        try { // since 2.10 remove "impossible" IOException as per [databind#1675]
//            return _readTreeAndClose(_jsonFactory.createParser(content));
//        } catch (JsonProcessingException e) {
//            throw e;
//        } catch (IOException e) { // shouldn't really happen but being declared need to
//            throw JsonMappingException.fromUnexpectedIOE(e);
//        }
//    }
//
//
//    public JsonNode readTree(byte[] content) throws IOException {
//        _assertNotNull("content", content);
//        return _readTreeAndClose(_jsonFactory.createParser(content));
//    }
//
//
//    public JsonNode readTree(byte[] content, int offset, int len) throws IOException {
//        _assertNotNull("content", content);
//        return _readTreeAndClose(_jsonFactory.createParser(content, offset, len));
//    }
//
//
//    public JsonNode readTree(File file) throws IOException
//    {
//        _assertNotNull("file", file);
//        return _readTreeAndClose(_jsonFactory.createParser(file));
//    }
//
//
//    @Deprecated // @since 2.20
//    public JsonNode readTree(URL source) throws IOException
//    {
//        _assertNotNull("source", source);
//        return _readTreeAndClose(_jsonFactory.createParser(source));
//    }
//
//
//
//
//
//    @Override
//    public void writeValue(JsonGenerator g, Object value)
//            throws IOException, StreamWriteException, DatabindException
//    {
//        _assertNotNull("g", g);
//        SerializationConfig config = getSerializationConfig();
//
//         12-May-2015/2.6, tatu: Looks like we do NOT want to call the usual
//         *    'config.initialize(g)` here, since it is assumed that generator
//         *    has been configured by caller.But for some reason we don't
//         *    trust indentation settings...
//         */
//        // 10-Aug-2012, tatu: as per [Issue#12], must handle indentation:
//        if (config.isEnabled(SerializationFeature.INDENT_OUTPUT)) {
//            if (g.getPrettyPrinter() == null) {
//                g.setPrettyPrinter(config.constructDefaultPrettyPrinter());
//            }
//        }
//        if (config.isEnabled(SerializationFeature.CLOSE_CLOSEABLE) && (value instanceof Closeable)) {
//            _writeCloseableValue(g, value, config);
//        } else {
//            _serializerProvider(config).serializeValue(g, value);
//            if (config.isEnabled(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)) {
//                g.flush();
//            }
//        }
//    }
//
//
//
//
//    @Override
//    public void writeTree(JsonGenerator g, TreeNode rootNode)
//            throws IOException
//    {
//        _assertNotNull("g", g);
//        SerializationConfig config = getSerializationConfig();
//        _serializerProvider(config).serializeValue(g, rootNode);
//        if (config.isEnabled(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)) {
//            g.flush();
//        }
//    }
//
//
//    public void writeTree(JsonGenerator g, JsonNode rootNode)
//            throws IOException
//    {
//        _assertNotNull("g", g);
//        SerializationConfig config = getSerializationConfig();
//        _serializerProvider(config).serializeValue(g, rootNode);
//        if (config.isEnabled(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)) {
//            g.flush();
//        }
//    }
//
//
//
//
//    @SuppressWarnings("unchecked")
//    @Override
//    public <T> T treeToValue(TreeNode n, Class<T> valueType)
//            throws IllegalArgumentException,
//            JsonProcessingException
//    {
//        if (n == null) {
//            return null;
//        }
//        try {
//            // 25-Jan-2019, tatu: [databind#2220] won't prevent existing coercions here
//            // Simple cast when we just want to cast to, say, ObjectNode
//            if (TreeNode.class.isAssignableFrom(valueType)
//                    && valueType.isAssignableFrom(n.getClass())) {
//                return (T) n;
//            }
//            final JsonToken tt = n.asToken();
//            // 20-Apr-2016, tatu: Another thing: for VALUE_EMBEDDED_OBJECT, assume similar
//            //    short-cut coercion
//            if (tt == JsonToken.VALUE_EMBEDDED_OBJECT) {
//                if (n instanceof POJONode) {
//                    Object ob = ((POJONode) n).getPojo();
//                    if ((ob == null) || valueType.isInstance(ob)) {
//                        return (T) ob;
//                    }
//                }
//            }
//            // 22-Aug-2019, tatu: [databind#2430] Consider "null node" (minor optimization)
//            // 08-Dec-2020, tatu: Alas, lead to [databind#2972], optimization gets complicated
//            //    so leave out for now...
//            if (tt == JsonToken.VALUE_NULL) {
//                 return null;
//            }*/
//            return readValue(treeAsTokens(n), valueType);
//        } catch (JsonProcessingException e) {
//            // 12-Nov-2020, tatu: These can legit happen, during conversion, especially
//            //   with things like Builders that validate arguments.
//            throw e;
//        } catch (IOException e) { // should not occur, no real i/o...
//            throw new IllegalArgumentException(e.getMessage(), e);
//        }
//    }
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T treeToValue(TreeNode n, JavaType valueType)
//            throws IllegalArgumentException,
//            JsonProcessingException
//    {
//        // Implementation copied from the type-erased variant
//        if (n == null) {
//            return null;
//        }
//        try {
//            if (valueType.isTypeOrSubTypeOf(TreeNode.class)
//                    && valueType.isTypeOrSuperTypeOf(n.getClass())) {
//                return (T) n;
//            }
//            final JsonToken tt = n.asToken();
//            if (tt == JsonToken.VALUE_EMBEDDED_OBJECT) {
//                if (n instanceof POJONode) {
//                    Object ob = ((POJONode) n).getPojo();
//                    if ((ob == null) || valueType.isTypeOrSuperTypeOf(ob.getClass())) {
//                        return (T) ob;
//                    }
//                }
//            }
//            return (T) readValue(treeAsTokens(n), valueType);
//        } catch (JsonProcessingException e) {
//            // 12-Nov-2020, tatu: These can legit happen, during conversion, especially
//            //   with things like Builders that validate arguments.
//            throw e;
//        } catch (IOException e) { // should not occur, no real i/o...
//            throw new IllegalArgumentException(e.getMessage(), e);
//        }
//    }
//
//
//    public <T> T treeToValue(TreeNode n, TypeReference<T> toValueTypeRef)
//            throws IllegalArgumentException,
//            JsonProcessingException
//    {
//        JavaType valueType = constructType(toValueTypeRef);
//        return treeToValue(n, valueType);
//    }
//
//
//    @SuppressWarnings({ "unchecked", "resource" })
//    public <T extends JsonNode> T valueToTree(Object fromValue)
//            throws IllegalArgumentException
//    {
//        // [databind#2430]: `null` should become "null node":
//        if (fromValue == null) {
//            return (T) getNodeFactory().nullNode();
//        }
//
//        // inlined 'writeValue' with minor changes:
//        // first: disable wrapping when writing
//        // [databind#4047] Fixes `SerializationFeature.WRAP_ROOT_VALUE` being ignored
//        final SerializationConfig config = getSerializationConfig();
//        final DefaultSerializerProvider context = _serializerProvider(config);
//
//        // Then create TokenBuffer to use as JsonGenerator
//        TokenBuffer buf = context.bufferForValueConversion(this);
//        if (isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)) {
//            buf = buf.forceUseOfBigDecimal(true);
//        }
//        try {
//            context.serializeValue(buf, fromValue);
//            try (JsonParser p = buf.asParser()) {
//                return readTree(p);
//            }
//        } catch (IOException e) { // should not occur, no real i/o...
//            throw new IllegalArgumentException(e.getMessage(), e);
//        }
//    }
//
//
//
//
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(File src, Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueType));
//    }
//
//
//    @SuppressWarnings({ "unchecked" })
//    public <T> T readValue(File src, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueTypeRef));
//    }
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(File src, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), valueType);
//    }
//
//
//    @Deprecated // @since 2.20
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(URL src, Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src),
//                _typeFactory.constructType(valueType));
//    }
//
//
//    @Deprecated // @since 2.20
//    @SuppressWarnings({ "unchecked" })
//    public <T> T readValue(URL src, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src),
//                _typeFactory.constructType(valueTypeRef));
//    }
//
//
//    @Deprecated // @since 2.20
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(URL src, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), valueType);
//    }
//
//
//    public <T> T readValue(String content, Class<T> valueType)
//            throws JsonProcessingException, JsonMappingException
//    {
//        _assertNotNull("content", content);
//        return readValue(content, _typeFactory.constructType(valueType));
//    }
//
//
//    public <T> T readValue(String content, TypeReference<T> valueTypeRef)
//            throws JsonProcessingException, JsonMappingException
//    {
//        _assertNotNull("content", content);
//        return readValue(content, _typeFactory.constructType(valueTypeRef));
//    }
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(String content, JavaType valueType)
//            throws JsonProcessingException, JsonMappingException
//    {
//        _assertNotNull("content", content);
//        try { // since 2.10 remove "impossible" IOException as per [databind#1675]
//            return (T) _readMapAndClose(_jsonFactory.createParser(content), valueType);
//        } catch (JsonProcessingException e) {
//            throw e;
//        } catch (IOException e) { // shouldn't really happen but being declared need to
//            throw JsonMappingException.fromUnexpectedIOE(e);
//        }
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(Reader src, Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueType));
//    }
//
//    @SuppressWarnings({ "unchecked" })
//    public <T> T readValue(Reader src, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueTypeRef));
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(Reader src, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), valueType);
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(InputStream src, Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueType));
//    }
//
//    @SuppressWarnings({ "unchecked" })
//    public <T> T readValue(InputStream src, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueTypeRef));
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(InputStream src, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), valueType);
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(byte[] src, Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueType));
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(byte[] src, int offset, int len,
//                           Class<T> valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src, offset, len), _typeFactory.constructType(valueType));
//    }
//
//    @SuppressWarnings({ "unchecked" })
//    public <T> T readValue(byte[] src, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), _typeFactory.constructType(valueTypeRef));
//    }
//
//    @SuppressWarnings({ "unchecked" })
//    public <T> T readValue(byte[] src, int offset, int len, TypeReference<T> valueTypeRef)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src, offset, len), _typeFactory.constructType(valueTypeRef));
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(byte[] src, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), valueType);
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(byte[] src, int offset, int len, JavaType valueType)
//            throws IOException, StreamReadException, DatabindException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src, offset, len), valueType);
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(DataInput src, Class<T> valueType) throws IOException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src),
//                _typeFactory.constructType(valueType));
//    }
//
//    @SuppressWarnings("unchecked")
//    public <T> T readValue(DataInput src, JavaType valueType) throws IOException
//    {
//        _assertNotNull("src", src);
//        return (T) _readMapAndClose(_jsonFactory.createParser(src), valueType);
//    }
//
//
//
//
//
//    public void writeValue(File resultFile, Object value)
//            throws IOException, StreamWriteException, DatabindException
//    {
//        _writeValueAndClose(createGenerator(resultFile, JsonEncoding.UTF8), value);
//    }
//
//
//    public void writeValue(OutputStream out, Object value)
//            throws IOException, StreamWriteException, DatabindException
//    {
//        _writeValueAndClose(createGenerator(out, JsonEncoding.UTF8), value);
//    }
//
//
//    public void writeValue(DataOutput out, Object value) throws IOException
//    {
//        _writeValueAndClose(createGenerator(out), value);
//    }
//
//
//    public void writeValue(Writer w, Object value)
//            throws IOException, StreamWriteException, DatabindException
//    {
//        _writeValueAndClose(createGenerator(w), value);
//    }
//
//
//    public String writeValueAsString(Object value)
//            throws JsonProcessingException
//    {
//        // alas, we have to pull the recycler directly here...
//        final BufferRecycler br = _jsonFactory._getBufferRecycler();
//        try (SegmentedStringWriter sw = new SegmentedStringWriter(br)) {
//            _writeValueAndClose(createGenerator(sw), value);
//            return sw.getAndClear();
//        } catch (JsonProcessingException e) {
//            throw e;
//        } catch (IOException e) { // shouldn't really happen, but is declared as possibility so:
//            throw JsonMappingException.fromUnexpectedIOE(e);
//        } finally {
//            br.releaseToPool(); // since 2.17
//        }
//    }
//
//
//    public byte[] writeValueAsBytes(Object value)
//            throws JsonProcessingException
//    {
//        final BufferRecycler br = _jsonFactory._getBufferRecycler();
//        try (ByteArrayBuilder bb = new ByteArrayBuilder(br)) {
//            _writeValueAndClose(createGenerator(bb, JsonEncoding.UTF8), value);
//            final byte[] result = bb.toByteArray();
//            bb.release();
//            return result;
//        } catch (JsonProcessingException e) {
//            throw e;
//        } catch (IOException e) { // shouldn't really happen, but is declared as possibility so:
//            throw JsonMappingException.fromUnexpectedIOE(e);
//        } finally {
//            br.releaseToPool(); // since 2.17
//        }
//    }
//
//
//
//
//
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T convertValue(Object fromValue, Class<T> toValueType)
//            throws IllegalArgumentException
//    {
//        return (T) _convert(fromValue, _typeFactory.constructType(toValueType));
//    }
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef)
//            throws IllegalArgumentException
//    {
//        return (T) _convert(fromValue, _typeFactory.constructType(toValueTypeRef));
//    }
//
//
//    @SuppressWarnings("unchecked")
//    public <T> T convertValue(Object fromValue, JavaType toValueType)
//            throws IllegalArgumentException
//    {
//        return (T) _convert(fromValue, toValueType);
//    }
//
