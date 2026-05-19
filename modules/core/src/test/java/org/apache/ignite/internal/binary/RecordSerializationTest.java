/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.marshaller.MarshallerContextTestImpl;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that Java records can be serialized and deserialized via the BinaryMarshaller.
 * Records are compiled in-memory since the project targets Java 11 source level.
 */
public class RecordSerializationTest {
    /**
     * Tests a simple record with primitive and String fields.
     */
    @Test
    public void testSimpleRecordRoundTrip() throws Exception {
        Class<?> recordCls = compileRecord(
            "SimpleRecord",
            "public record SimpleRecord(int id, String name, long value) {}"
        );

        Object record = instantiateRecord(recordCls, 42, "hello", 100L);

        BinaryMarshaller marsh = marshaller();

        byte[] bytes = marsh.marshal(record);
        Object result = marsh.unmarshal(bytes, null);

        assertEquals(record, result);
    }

    /**
     * Tests a record with boxed and primitive types.
     */
    @Test
    public void testRecordWithBoxedTypes() throws Exception {
        Class<?> recordCls = compileRecord(
            "BoxedRecord",
            "public record BoxedRecord(Integer id, Boolean flag, Double score) {}"
        );

        Object record = instantiateRecord(recordCls, 99, true, 3.14);

        BinaryMarshaller marsh = marshaller();

        byte[] bytes = marsh.marshal(record);
        Object result = marsh.unmarshal(bytes, null);

        assertEquals(record, result);
    }

    /**
     * Tests a record with nested record fields.
     */
    @Test
    public void testNestedRecord() throws Exception {
        Class<?> innerCls = compileRecord(
            "InnerRecord",
            "public record InnerRecord(int x, int y) {}"
        );

        Class<?> outerCls = compileRecord(
            "OuterRecord",
            "public record OuterRecord(String label, InnerRecord point) {}"
        );

        Object inner = instantiateRecord(innerCls, 10, 20);
        Object outer = instantiateRecord(outerCls, "point", inner);

        BinaryMarshaller marsh = marshaller();

        byte[] bytes = marsh.marshal(outer);
        Object result = marsh.unmarshal(bytes, null);

        assertEquals(outer, result);
    }

    /**
     * Tests an empty record (no fields).
     */
    @Test
    public void testEmptyRecord() throws Exception {
        Class<?> recordCls = compileRecord(
            "EmptyRecord",
            "public record EmptyRecord() {}"
        );

        Object record = instantiateRecord(recordCls);

        BinaryMarshaller marsh = marshaller();

        byte[] bytes = marsh.marshal(record);
        Object result = marsh.unmarshal(bytes, null);

        assertEquals(record, result);
    }

    /**
     * Creates a BinaryMarshaller for testing.
     */
    private static BinaryMarshaller marshaller() throws Exception {
        BinaryMarshaller marsh = new BinaryMarshaller();
        marsh.setContext(new MarshallerContextTestImpl());
        marsh.setBinaryContext(U.binaryContext(marsh, new IgniteConfiguration()));
        return marsh;
    }

    /**
     * Compiles a Java record from source string using the in-memory compiler.
     *
     * @param className Simple class name of the record (without package).
     * @param source Source code of the record.
     * @return The compiled record class.
     */
    private static Class<?> compileRecord(String className, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System Java compiler not found", compiler);

        InMemoryFileManager fileMgr = new InMemoryFileManager(
            compiler.getStandardFileManager(null, null, null)
        );

        JavaFileObject javaFile = new SimpleJavaFileObject(
            URI.create("string:///" + className + ".java"),
            JavaFileObject.Kind.SOURCE
        ) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        List<String> options = List.of("--release", "16");

        JavaCompiler.CompilationTask task = compiler.getTask(
            null, fileMgr, null, options, null, List.of(javaFile)
        );

        assertTrue("Compilation failed for: " + className, task.call());

        Map<String, byte[]> classes = fileMgr.getClasses();
        ClassLoader cl = new ClassLoader() {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes != null)
                    return defineClass(name, bytes, 0, bytes.length);
                return super.findClass(name);
            }
        };

        return cl.loadClass(className);
    }

    /**
     * Creates a record instance using its canonical constructor.
     *
     * @param cls Record class.
     * @param args Constructor arguments.
     * @return Record instance.
     */
    private static Object instantiateRecord(Class<?> cls, Object... args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++)
            paramTypes[i] = args[i].getClass();

        // Handle primitive widening for constructors
        Constructor<?>[] ctors = cls.getDeclaredConstructors();
        Constructor<?> match = null;

        for (Constructor<?> ctor : ctors) {
            Class<?>[] ctorParamTypes = ctor.getParameterTypes();
            if (ctorParamTypes.length != args.length)
                continue;

            boolean matches = true;
            for (int i = 0; i < ctorParamTypes.length; i++) {
                if (!matches(ctorParamTypes[i], args[i]))
                    matches = false;
            }

            if (matches) {
                match = ctor;
                break;
            }
        }

        assertNotNull("No matching constructor found for: " + cls.getName(), match);

        // Box primitives to their wrapper types for constructor invocation
        Object[] boxedArgs = new Object[args.length];
        Class<?>[] matchParamTypes = match.getParameterTypes();
        for (int i = 0; i < args.length; i++)
            boxedArgs[i] = boxIfNeeded(args[i], matchParamTypes[i]);

        return match.newInstance(boxedArgs);
    }

    /**
     * Checks if a value's type matches a target parameter type (with primitive widening).
     */
    private static boolean matches(Class<?> paramType, Object arg) {
        if (paramType.isPrimitive()) {
            Class<?> wrapped = wrap(paramType);
            return wrapped.isAssignableFrom(arg.getClass());
        }
        return paramType.isAssignableFrom(arg.getClass());
    }

    /**
     * Boxes a primitive value to its wrapper type if needed for constructor invocation.
     */
    private static Object boxIfNeeded(Object arg, Class<?> targetType) {
        if (targetType.isPrimitive() && arg instanceof Integer) {
            if (targetType == int.class) return arg;
            if (targetType == long.class) return ((Integer)arg).longValue();
            if (targetType == double.class) return ((Integer)arg).doubleValue();
            if (targetType == float.class) return ((Integer)arg).floatValue();
            if (targetType == short.class) return ((Integer)arg).shortValue();
            if (targetType == byte.class) return ((Integer)arg).byteValue();
        }
        if (targetType.isPrimitive() && arg instanceof Long) {
            if (targetType == long.class) return arg;
            if (targetType == double.class) return ((Long)arg).doubleValue();
            if (targetType == float.class) return ((Long)arg).floatValue();
        }
        if (targetType.isPrimitive() && arg instanceof Double) {
            if (targetType == double.class) return arg;
            if (targetType == float.class) return ((Double)arg).floatValue();
        }
        if (targetType.isPrimitive() && arg instanceof Boolean)
            return arg;
        return arg;
    }

    /**
     * Returns the wrapper class for a primitive type.
     */
    private static Class<?> wrap(Class<?> prim) {
        if (prim == int.class) return Integer.class;
        if (prim == long.class) return Long.class;
        if (prim == double.class) return Double.class;
        if (prim == float.class) return Float.class;
        if (prim == boolean.class) return Boolean.class;
        if (prim == byte.class) return Byte.class;
        if (prim == short.class) return Short.class;
        if (prim == char.class) return Character.class;
        return prim;
    }

    /**
     * In-memory Java file manager that stores compiled bytecode in a map.
     */
    private static class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, byte[]> classes = new HashMap<>();

        InMemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling
        ) {
            return new SimpleJavaFileObject(
                URI.create("mem:///" + className.replace('.', '/') + kind.extension),
                kind
            ) {
                @Override public OutputStream openOutputStream() {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream() {
                        @Override public void close() throws IOException {
                            super.close();
                            classes.put(className, toByteArray());
                        }
                    };
                    return baos;
                }
            };
        }

        Map<String, byte[]> getClasses() {
            return classes;
        }
    }
}
