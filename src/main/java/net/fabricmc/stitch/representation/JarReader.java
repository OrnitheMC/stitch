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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import net.fabricmc.stitch.merge.ClassMerger;
import net.fabricmc.stitch.representation.JarClassEntry.ClassEntryPopulator;
import net.fabricmc.stitch.util.StitchUtil;
import net.ornithemc.nester.nest.Nest;

public class JarReader
{
    private final Classpath classpath;

    public JarReader(Classpath classpath) {
        this.classpath = classpath;
    }

    public void apply() throws IOException {
        apply(new byte[] { });
    }

    public void apply(byte[] salt) throws IOException {
        // Stage 1: read .JAR class/field/method meta
        this.readJar(this.classpath.getJar());
        System.err.println("Read " + this.classpath.getJar().getAllClasses().size() + " (" + this.classpath.getJar().getClasses().size() + ") classes.");
        int missing = 0;
        for (Nest nest : this.classpath.getNests()) {
            String cls = nest.enclClassName;

            if (this.classpath.getJar().getClass(cls, null) == null) {
                ClassEntryPopulator populator = new ClassEntryPopulator();

                populator.access = 0;
                populator.name = cls;
                populator.superclass = "java/lang/Object";
                populator.interfaces = new String[0];

                this.classpath.getJar().getClass(populator.name, populator);
                System.err.println("Created " + cls);

                missing++;
            }
        }
        if (missing > 0) {
            System.err.println("Read " + missing + " missing classes from nests");
        }

        // Stage 2: read classpath class/method meta
        for (JarRootEntry lib : this.classpath.classpath) {
            if (lib != this.classpath.getJar()) {
                this.readClasspath(lib);
            }
        }
        System.err.println("Read libraries.");

        // Stage 2: find subclasses
        this.classpath.getJar().getAllClasses().forEach((c) -> c.populateParents(this.classpath));
        System.err.println("Populated subclass entries.");

        // Stage 3: find inner classes
        this.classpath.getJar().getAllClasses().forEach((c) -> c.populateInnerClasses(this.classpath.getJar()));
        System.err.println("Populated inner class entries.");

        // Stage 4: find classes in the same package
        this.classpath.getJar().getAllClasses().forEach((c) -> c.populateSiblings(this.classpath.getJar()));
        System.err.println("Populated sibling class entries.");

        // Stage 5: hashing
        this.classpath.getJar().hash(salt);
        System.err.println("Hashed jar entries.");

        for (JarRootEntry jar : classpath.classpath) {
            jar.classQueue.clear();
        }

        System.err.println("- Done. -");
    }

    private void readJar(JarRootEntry jar) throws IOException {
        try (FileInputStream fileStream = new FileInputStream(jar.file)) {
            try (JarInputStream jarStream = new JarInputStream(fileStream)) {
                java.util.jar.JarEntry entry;

                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }

                    ClassReader reader = new ClassReader(jarStream);
                    ClassVisitor visitor = new ClassVisitor(StitchUtil.ASM_VERSION, null)
                    {
                        private final Set<JarFieldEntry> fields = new LinkedHashSet<>();
                        private final Set<JarMethodEntry> methods = new LinkedHashSet<>();
                        private ClassEntryPopulator populator;
                        private long startedAt;

                        @SuppressWarnings("deprecation")
                        @Override
                        public void visit(final int version, final int access, final String name, final String signature,
                                          final String superName, final String[] interfaces) {
//                            startedAt = System.nanoTime();
                            populator = new ClassEntryPopulator();

                            populator.access = access;
                            populator.name = name;
                            populator.signature = signature;
                            populator.superclass = superName;
                            populator.interfaces = interfaces;

                            super.visit(version, access, name, signature, superName, interfaces);
                        }

                        @Override
                        public FieldVisitor visitField(final int access, final String name, final String descriptor,
                                                       final String signature, final Object value) {
                            JarFieldEntry field = new JarFieldEntry(access, name, descriptor, signature, populator.name);
                            fields.add(field);

                            return new FieldVisitor(StitchUtil.ASM_VERSION, super.visitField(access, name, descriptor, signature, value)) {

                                @Override
                                public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
                                    return new EnvironmentAnnotationReader(StitchUtil.ASM_VERSION, super.visitAnnotation(descriptor, visible), descriptor, field::setSide);
                                }
                            };
                        }

                        @Override
                        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                                         final String signature, final String[] exceptions) {
                            JarMethodEntry method = new JarMethodEntry(access, name, descriptor, signature, populator.name);
                            methods.add(method);

                            return new MethodVisitor(StitchUtil.ASM_VERSION, super.visitMethod(access, name, descriptor, signature, exceptions)) {

                                @Override
                                public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
                                    return new EnvironmentAnnotationReader(StitchUtil.ASM_VERSION, super.visitAnnotation(descriptor, visible), descriptor, method::setSide);
                                }
                            };
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
                            return new EnvironmentAnnotationReader(StitchUtil.ASM_VERSION, super.visitAnnotation(descriptor, visible), descriptor, side -> populator.side = side);
                        }

                        @Override
                        public void visitOuterClass(final String owner, final String name, final String descriptor) {
                            populator.enclosingClassName = owner;
                            populator.enclosingMethodName = name;
                            populator.enclosingMethodDescriptor = descriptor;

                            super.visitOuterClass(owner, name, descriptor);
                        }

                        @Override
                        public void visitInnerClass(final String name, final String outerName, final String innerName,
                                                    final int access) {
                            if (populator.name.equals(name)) {
                                populator.nested = true; 
                                populator.declaringClassName = outerName;
                                populator.innerName = innerName;
                                populator.innerAccess = access;
                            }

                            super.visitInnerClass(name, outerName, innerName, access);
                        }

                        @Override
                        public void visitEnd() {
                            JarClassEntry classEntry = jar.getClass(populator.name, populator);

                            for (JarFieldEntry fieldEntry : fields) {
                                classEntry.fields.put(fieldEntry.getKey(), fieldEntry);
                            }
                            for (JarMethodEntry methodEntry : methods) {
                                classEntry.methods.put(methodEntry.getKey(), methodEntry);
                            }

//                            long timeSpan = (System.nanoTime() - startedAt) / 1000;
//                            System.err.println("Loaded " + classEntry.getKey() + " in " + timeSpan + "μs");

                            super.visitEnd();
                        }
                    };
                    reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    private void readClasspath(JarRootEntry jar) throws IOException {
        try (FileInputStream fileStream = new FileInputStream(jar.file)) {
            try (JarInputStream jarStream = new JarInputStream(fileStream)) {
                java.util.jar.JarEntry entry;

                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (entry.getName().endsWith(".class")) {
                        jar.classQueue.add(entry.getName().substring(0, entry.getName().length() - ".class".length()));
                    }
                }
            }
        }
    }

    static JarClassEntry readFromClasspath(JarRootEntry jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.file)) {
            ZipEntry entry = zip.getEntry(name + ".class");

            if (entry != null) {
                return readFromInputStream(jar, zip.getInputStream(entry));
            }

            return null;
        }
    }

    static JarClassEntry readFromJre(JarRootEntry jar, String name) {
        try {
            InputStream is = ClassLoader.getSystemResourceAsStream(name + ".class");

            if (is != null) {
                return readFromInputStream(jar, is);
            }
        } catch (IOException e) {
        }

        return null;
    }

    private static JarClassEntry readFromInputStream(JarRootEntry jar, InputStream is) throws IOException {
        ClassReader reader = new ClassReader(is);
        ClassVisitor visitor = new ClassVisitor(StitchUtil.ASM_VERSION, null)
        {
            private final Set<JarMethodEntry> methods = new LinkedHashSet<>();
            private ClassEntryPopulator populator;

            @Override
            public void visit(final int version, final int access, final String name, final String signature,
                              final String superName, final String[] interfaces) {
                populator = new ClassEntryPopulator();

                populator.access = access;
                populator.name = name;
                populator.signature = signature;
                populator.superclass = superName;
                populator.interfaces = interfaces;

                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                methods.add(new JarMethodEntry(access, name, descriptor, signature, populator.name));

                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                JarClassEntry classEntry = jar.getClass(populator.name, populator);

                for (JarMethodEntry methodEntry : methods) {
                    classEntry.methods.put(methodEntry.getKey(), methodEntry);
                }

                super.visitEnd();
            }
        };
        reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return jar.getClass(reader.getClassName(), null);
    }

    private static class EnvironmentAnnotationReader extends AnnotationVisitor {

        private final String annotationDescriptor;
        private final Consumer<Side> setter;

        private Boolean parseValue;

        EnvironmentAnnotationReader(int api, AnnotationVisitor annotationVisitor, String descriptor, Consumer<Side> setter) {
            super(api, annotationVisitor);

            this.annotationDescriptor = descriptor;
            this.setter = setter;
        }

        @Override
        public void visitEnum(final String name, final String descriptor, final String value) {
            if (parseValue == null) {
                parseValue = ClassMerger.SIDED_DESCRIPTOR.equals(annotationDescriptor);
            }

            if (parseValue && "value".equals(name) && ClassMerger.SIDE_DESCRIPTOR.equals(descriptor)) {
                if ("CLIENT".equals(value)) {
                    setter.accept(Side.CLIENT);
                }
                if ("SERVER".equals(value)) {
                    setter.accept(Side.SERVER);
                }
            }
        }
    }
}
