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
import java.util.function.BiConsumer;

import org.objectweb.asm.Type;

import net.fabricmc.mapping.util.EntryTriple;

public class IntermediaryCombiner {

    private Map<String, ClassEntry> clientIntermediary = new TreeMap<>();
    private Map<String, ClassEntry> serverIntermediary = new TreeMap<>();

    public void readClient(Path input, Reader r) throws IOException {
        r.accept(input, this::addClientClass, this::addClientField, this::addClientMethod);
    }

    public void readServer(Path input, Reader r) throws IOException {
        r.accept(input, this::addServerClass, this::addServerField, this::addServerMethod);
    }

    public void writeCombined(ClassWriter clsw, FieldWriter fldw, MethodWriter mtdw) throws IOException {
        Map<String, ClassEntry> invertedClient = new TreeMap<>();
        Map<String, ClassEntry> invertedServer = new TreeMap<>();

        invert(clientIntermediary, invertedClient);
        invert(serverIntermediary, invertedServer);

        clientIntermediary = invertedClient;
        serverIntermediary = invertedServer;

        for (ClassEntry cCls : clientIntermediary.values()) {
            ClassEntry sCls = serverIntermediary.get(cCls.name);

            if (sCls == null) {
                clsw.accept(cCls.name, cCls.target, "");
            } else {
                clsw.accept(cCls.name, cCls.target, sCls.target);
            }

            for (FieldEntry cFld : cCls.fields.values()) {
                FieldEntry sFld = (sCls == null) ? null : sCls.fields.get(cFld.name + cFld.desc);

                if (sFld == null) {
                    fldw.accept(cCls.name, cFld.name, cFld.desc, cFld.target, "");
                } else {
                    fldw.accept(cCls.name, cFld.name, cFld.desc, cFld.target, sFld.target); 
                }
            }
            for (MethodEntry cMtd : cCls.methods.values()) {
                MethodEntry sMtd = (sCls == null) ? null : sCls.methods.get(cMtd.name + cMtd.desc);

                if (sMtd == null) {
                    mtdw.accept(cCls.name, cMtd.name, cMtd.desc, cMtd.target, "");
                } else {
                    mtdw.accept(cCls.name, cMtd.name, cMtd.desc, cMtd.target, sMtd.target); 
                }
            }
        }
        for (ClassEntry sCls : serverIntermediary.values()) {
            ClassEntry cCls = clientIntermediary.get(sCls.name);

            if (cCls == null) {
                clsw.accept(sCls.name, "", sCls.target);
            }

            for (FieldEntry sFld : sCls.fields.values()) {
                FieldEntry cFld = (cCls == null) ? null : cCls.fields.get(sFld.name + sFld.desc);

                if (cFld == null) {
                    fldw.accept(sCls.name, sFld.name, sFld.desc, "", sFld.target);
                }
            }
            for (MethodEntry sMtd : sCls.methods.values()) {
                MethodEntry cMtd = (cCls == null) ? null : cCls.methods.get(sMtd.name + sMtd.desc);

                if (cMtd == null) {
                    mtdw.accept(sCls.name, sMtd.name, sMtd.desc, "", sMtd.target);
                }
            }
        }
    }

    private void addClientClass(String name, String target) {
        addClass("client", clientIntermediary, name, target);
    }

    private void addClientField(EntryTriple src, String target) {
        addField("client", clientIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), target);
    }

    private void addClientMethod(EntryTriple src, String target) {
        addMethod("client", clientIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), target);
    }

    private void addServerClass(String name, String target) {
        addClass("server", serverIntermediary, name, target);
    }

    private void addServerField(EntryTriple src, String target) {
        addField("server", serverIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), target);
    }

    private void addServerMethod(EntryTriple src, String target) {
        addMethod("server", serverIntermediary, src.getOwner(), src.getName(), src.getDescriptor(), target);
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
            ClassEntry invCls = new ClassEntry(cls.target, cls.name);
            dst.put(invCls.name, invCls);

            for (FieldEntry fld : cls.fields.values()) {
                FieldEntry invFld = new FieldEntry(fld.target, remapFieldDescriptor(fld.desc, src), fld.name);
                invCls.fields.put(invFld.name + invFld.desc, invFld);
            }
            for (MethodEntry mtd : cls.methods.values()) {
                MethodEntry invMtd = new MethodEntry(mtd.target, remapMethodDescriptor(mtd.desc, src), mtd.name);
                invCls.methods.put(invMtd.name + invMtd.desc, invMtd);
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
    public interface Reader {
        void accept(Path input, BiConsumer<String, String> cls, BiConsumer<EntryTriple, String> fld, BiConsumer<EntryTriple, String> mtd) throws IOException;
    }

    @FunctionalInterface
    public interface ClassWriter {
        void accept(String target, String client, String server) throws IOException;
    }

    @FunctionalInterface
    public interface FieldWriter {
        void accept(String targetCls, String target, String targetDesc, String client, String server) throws IOException;
    }

    @FunctionalInterface
    public interface MethodWriter {
        void accept(String targetCls, String target, String targetDesc, String client, String server) throws IOException;
    }
}
