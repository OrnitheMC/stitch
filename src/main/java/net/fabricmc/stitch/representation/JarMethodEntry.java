/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 * Modifications copyright (c) 2022 OrnitheMC
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

package net.fabricmc.stitch.representation;

import org.objectweb.asm.Type;

public class JarMethodEntry extends AbstractJarEntry
{
    protected String desc;
    protected String signature;

    MethodHierarchy hierarchy;

    String bridgeMethod;
    String specializedMethod;

    protected JarMethodEntry(int access, String name, String desc, String signature, String parentName) {
        super(name, parentName);
        this.setAccess(access);
        this.desc = desc;
        this.signature = signature;
    }

    public String getDescriptor() {
        return desc;
    }

    public String getSignature() {
        return signature;
    }

    public MethodHierarchy getHierarchy() {
        return hierarchy;
    }

    void setSpecializedMethod(String method) {
        specializedMethod = method;
    }

    public String getSpecializedMethodName() {
        return specializedMethod;
    }

    public JarMethodEntry getSpecializedMethod(Classpath storage, JarClassEntry c) {
        return specializedMethod == null ? null : c.methods.get(specializedMethod);
    }

    public String getBridgeMethodName() {
        return bridgeMethod;
    }

    public JarMethodEntry getBridgeMethod(Classpath storage, JarClassEntry c) {
        return bridgeMethod == null ? null : c.methods.get(bridgeMethod);
    }

    public JarClassEntry getParentClass(Classpath storage) {
        return storage.getClass(parentName);
    }

    void populateSubclasses(Classpath storage, JarClassEntry c) {
        if (specializedMethod != null) {
            JarMethodEntry sm = c.getMethod(specializedMethod);

            if (sm != null) {
                populateSubclasses(storage);
                sm.populateSubclasses(storage);
            }
        }
    }

    private void populateSubclasses(Classpath storage) {
        Type type = Type.getType(desc);

        Type[] argTypes = type.getArgumentTypes();
        Type returnType = type.getReturnType();

        for (Type argType : argTypes) {
            populateSubclasses(storage, argType);
        }
        populateSubclasses(storage, returnType);
    }

    private void populateSubclasses(Classpath storage, Type type) {
        if (type.getSort() == Type.ARRAY) {
            populateSubclasses(storage, type.getElementType());
        } else if (type.getSort() == Type.OBJECT) {
            JarClassEntry c = storage.findClass(type.getInternalName());

            if (c != null && !c.isMainJar(storage)) {
                c.populateSubclasses(storage);
            }
        }
    }

    void populateBridgeMethod(Classpath storage, JarClassEntry c) {
        String candidate = specializedMethod;
        specializedMethod = null;

        if (candidate != null && c.methods.containsKey(candidate) && existsInSuperClasses(storage, c, name + desc)) {
            int i = candidate.indexOf('(');
            String candidateDesc = candidate.substring(i);

            if (areMethodsBridgeCompatible(storage, desc, candidateDesc)) {
                JarMethodEntry sm = c.methods.get(candidate);

                specializedMethod = sm.getKey();
                sm.bridgeMethod = getKey();
            }
        }
    }

    private static boolean existsInSuperClasses(Classpath storage, JarClassEntry cls, String method) {
        for (JarClassEntry superClass : cls.getSuperClasses(storage)) {
            if (superClass.methods.containsKey(method) || existsInSuperClasses(storage, superClass, method)) {
                return true;
            }
        }

        return false;
    }

    private static boolean areMethodsBridgeCompatible(Classpath storage, String bridgeDescriptor, String specializedDescriptor) {
        Type bridgeType = Type.getType(bridgeDescriptor);
        Type specializedType = Type.getType(specializedDescriptor);

        Type[] bridgeArgTypes = bridgeType.getArgumentTypes();
        Type bridgeReturnType = bridgeType.getReturnType();
        Type[] specializedArgTypes = specializedType.getArgumentTypes();
        Type specializedReturnType = specializedType.getReturnType();

        if (bridgeArgTypes.length != specializedArgTypes.length) {
            return false;
        }

        for (int i = 0; i < bridgeArgTypes.length; i++) {
            if (!areTypesBridgeCompatible(storage, bridgeArgTypes[i], specializedArgTypes[i])) {
                return false;
            }
        }

        return areTypesBridgeCompatible(storage, bridgeReturnType, specializedReturnType);
    }

    private static boolean areTypesBridgeCompatible(Classpath storage, Type typeForBridge, Type typeForSpecialized) {
        if (typeForBridge.equals(typeForSpecialized)) {
            return true;
        }
        if (typeForBridge.getSort() != typeForSpecialized.getSort()) {
            return false;
        }

        switch (typeForBridge.getSort()) {
        case Type.OBJECT:
            JarClassEntry clsForBridge = storage.getClass(typeForBridge.getInternalName());
            JarClassEntry clsForSpecialized = storage.getClass(typeForSpecialized.getInternalName());

            return areClassTypesBridgeCompatible(storage, clsForBridge, clsForSpecialized);
        case Type.ARRAY:
            if (typeForBridge.getDimensions() != typeForSpecialized.getDimensions()) {
                return false;
            }

            return areTypesBridgeCompatible(storage, typeForBridge.getElementType(), typeForSpecialized.getElementType());
        }

        return false;
    }

    private static boolean areClassTypesBridgeCompatible(Classpath storage, JarClassEntry clsForBridge, JarClassEntry clsForSpecialized) {
        if (clsForBridge == clsForSpecialized) {
            return true;
        }
        if (clsForBridge == null || clsForSpecialized == null) {
            return false;
        }
        if ("java/lang/Object".equals(clsForSpecialized.name)) {
            return false;
        }

        for (JarClassEntry superClsForSpecialized : clsForSpecialized.getSuperClasses(storage)) {
            if (areClassTypesBridgeCompatible(storage, clsForBridge, superClsForSpecialized)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected String getKey() {
        return super.getKey() + desc;
    }

    @Override
    public char getPrefix() {
        return 'm';
    }

    @Override
    public boolean isSerializable(Classpath storage) {
        if (Access.isPrivate(access)) {
            // these methods are specific to Serializable classes, but are private
            if (!("writeObject".equals(name) && "(Ljava/io/ObjectOutputStream;)V".equals(desc))
                && !("readObject".equals(name) && "(Ljava/io/ObjectInputStream;)V".equals(desc))
                && !("writeReplace".equals(name) && "()Ljava/lang/Object;".equals(desc))
                && !("readResolve".equals(name) && "()Ljava/lang/Object;".equals(desc))) {
                return false;
            }
        }

        JarClassEntry parent = storage.getClass(parentName);
        return parent != null && parent.isSerializable(storage);
    }

    @Override
    public boolean isMainJar(Classpath storage) {
        return storage.getClass(parentName).isMainJar(storage);
    }
}