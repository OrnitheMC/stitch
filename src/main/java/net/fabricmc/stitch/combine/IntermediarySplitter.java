/*
 * Copyright (c) 2024 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.combine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.objectweb.asm.Type;

import net.fabricmc.mapping.util.EntryTriple;

public class IntermediarySplitter {

    private Map<String, ClassEntry> clientIntermediary = new TreeMap<>();
    private Map<String, ClassEntry> serverIntermediary = new TreeMap<>();

    public void read(Path input, Reader r) throws IOException {
        r.accept(input, this::addClass, this::addField, this::addMethod);
    }

    public void write(Path clientOutput, Path serverOutput, Writer writer) throws IOException {
        splitMappingsToOutput(clientIntermediary, clientOutput, new SafeWriter(writer));
        splitMappingsToOutput(serverIntermediary, serverOutput, new SafeWriter(writer));
    }

    private void addClass(String name, String client, String server) {
        if (client != null) {
            addClass("client", clientIntermediary, name, client);
        }
        if (server != null) {
            addClass("server", serverIntermediary, name, server);
        }
    }

    private void addField(EntryTriple src, String client, String server) {
        if (client != null) {
            addField("client", clientIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), client);
        }
        if (server != null) {
            addField("server", serverIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), server);
        }
    }

    private void addMethod(EntryTriple src, String client, String server) {
        if (client != null) {
            addMethod("client", clientIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), client);
        }
        if (server != null) {
            addMethod("server", serverIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), server);
        }
    }

    private void addClass(String side, Map<String, ClassEntry> intermediary, String name, String target) {
        intermediary.compute(name, (key, cls) -> {
            if (cls != null) {
                throw new IllegalStateException("duplicate class " + name + " in " + side + " intermediary!");
            }

            return new ClassEntry(name, target);
        });
    }

    private void addField(String side, Map<String, ClassEntry> intermediary, String clsName, String name, String desc, String target) {
        intermediary.compute(clsName, (key, cls) -> {
            if (cls == null) {
                throw new IllegalStateException("unknown class " + clsName + " for field " + name + desc + " in " + side + " intermediary!");
            }

            cls.fields.compute(name + desc, (fkey, fld) -> {
                if (fld != null) {
                    throw new IllegalStateException("duplicate field " + name + desc + " in class " + clsName + " in " + side + " intermediary!");
                }

                return new FieldEntry(name, desc, target);
            });

            return cls;
        });
    }

    private void addMethod(String side, Map<String, ClassEntry> intermediary, String clsName, String name, String desc, String target) {
        intermediary.compute(clsName, (key, cls) -> {
            if (cls == null) {
                throw new IllegalStateException("unknown class " + clsName + " for method " + name + desc + " in " + side + " intermediary!");
            }

            cls.methods.compute(name + desc, (mkey, mtd) -> {
                if (mtd != null) {
                    throw new IllegalStateException("duplicate method " + name + desc + " in class " + clsName + " in " + side + " intermediary!");
                }

                return new MethodEntry(name, desc, target);
            });

            return cls;
        });
    }

    private void invert(Map<String, ClassEntry> src, Map<String, ClassEntry> dst) {
        for (ClassEntry cls : src.values()) {
            if (cls.target != null) {
                ClassEntry invCls = new ClassEntry(cls.target, cls.name);
                dst.put(invCls.name, invCls);

                for (FieldEntry fld : cls.fields.values()) {
                    if (fld.target != null) {
                        FieldEntry invFld = new FieldEntry(fld.target, remapFieldDescriptor(fld.desc, src), fld.name);
                        invCls.fields.put(invFld.name + invFld.desc, invFld);
                    }
                }
                for (MethodEntry mtd : cls.methods.values()) {
                    if (mtd.target != null) {
                        MethodEntry invMtd = new MethodEntry(mtd.target, remapMethodDescriptor(mtd.desc, src), mtd.name);
                        invCls.methods.put(invMtd.name + invMtd.desc, invMtd);
                    }
                }
            }
        }
    }

    public String remapFieldDescriptor(String desc, Map<String, ClassEntry> classes) {
        Type type = Type.getType(desc);
        type = remapType(type, classes);

        return type.getDescriptor();
    }

    public String remapMethodDescriptor(String desc, Map<String, ClassEntry> classes) {
        Type type = Type.getMethodType(desc);

        Type[] argTypes = type.getArgumentTypes();
        Type returnType = type.getReturnType();

        for (int i = 0; i < argTypes.length; i++) {
            argTypes[i] = remapType(argTypes[i], classes);
        }
        returnType = remapType(returnType, classes);

        type = Type.getMethodType(returnType, argTypes);

        return type.getDescriptor();
    }

    public Type remapType(Type type, Map<String, ClassEntry> classes) {
        switch (type.getSort()) {
        case Type.OBJECT:
            String className = type.getInternalName();
            ClassEntry cls = classes.get(className);
            if (cls != null) {
                type = Type.getObjectType(cls.target);
            }

            break;
        case Type.ARRAY:
            Type elementType = type.getElementType();
            elementType = remapType(elementType, classes);

            int numDim = type.getDimensions();
            String desc = "";

            for (int i = 0; i < numDim; i++) {
                desc += "[";
            }

            desc += elementType.getDescriptor();
            type = Type.getType(desc);

            break;
        }

        return type;
    }

    private void splitMappingsToOutput(Map<String, ClassEntry> intermediary, Path output, SafeWriter writer) throws IOException {
        if (!intermediary.isEmpty()) {
            Map<String, ClassEntry> inverted = new TreeMap<>();
            invert(intermediary, inverted);

            writer.open(output);

            for (ClassEntry cls : inverted.values()) {
                writer.acceptClass(cls.name, cls.target);

                for (FieldEntry fld : cls.fields.values()) {
                    writer.acceptField(cls.name, fld.name, fld.desc, fld.target);
                }
                for (MethodEntry mtd : cls.methods.values()) {
                    writer.acceptMethod(cls.name, mtd.name, mtd.desc, mtd.target);
                }
            }

            writer.close();
        }
    }

    private static class Entry {

        public final Type type;
        public final String name, desc, target;

        private Entry(Type type, String name, String desc, String target) {
            this.type = type;
            this.name = name;
            this.desc = desc;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Entry) {
                Entry e = (Entry) o;
                return type == e.type && Objects.equals(name, e.name) && Objects.equals(desc, e.desc) && Objects.equals(target, e.target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, name, desc, target);
        }

        public enum Type {
            CLASS, FIELD, METHOD
        }
    }

    private static class ClassEntry extends Entry {

        private final Map<String, FieldEntry> fields;
        private final Map<String, MethodEntry> methods;

        public ClassEntry(String name, String target) {
            super(Type.CLASS, name, null, target);

            this.fields = new LinkedHashMap<>();
            this.methods = new LinkedHashMap<>();
        }
    }

    private static class FieldEntry extends Entry {

        public FieldEntry(String name, String desc, String target) {
            super(Type.FIELD, name, desc, target);
        }
    }

    private static class MethodEntry extends Entry {

        public MethodEntry(String name, String desc, String target) {
            super(Type.METHOD, name, desc, target);
        }
    }

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    @FunctionalInterface
    public interface Reader {
        void accept(Path input, TriConsumer<String, String, String> cls, TriConsumer<EntryTriple, String, String> fld, TriConsumer<EntryTriple, String, String> mtd) throws IOException;
    }

    public interface Writer {
        boolean open(Path path) throws IOException;
        void acceptClass(String cls, String target) throws IOException;
        void acceptField(String cls, String name, String desc, String target) throws IOException;
        void acceptMethod(String cls, String name, String desc, String target) throws IOException;
        void close() throws IOException;
    }

    private static class SafeWriter implements Writer{

        private final Writer delegate;
        private boolean active;

        public SafeWriter(Writer delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean open(Path path) throws IOException {
            return active = delegate.open(path);
        }

        @Override
        public void acceptClass(String name, String target) throws IOException {
            if (active) {
                delegate.acceptClass(name, target);
            }
        }

        @Override
        public void acceptField(String cls, String name, String desc, String target) throws IOException {
            if (active) {
                delegate.acceptField(cls, name, desc, target);
            }
        }

        @Override
        public void acceptMethod(String cls, String name, String desc, String target) throws IOException {
            if (active) {
                delegate.acceptMethod(cls, name, desc, target);
            }
        }

        @Override
        public void close() throws IOException{
            if (active) {
                delegate.close();
            }
        }
    }
}
