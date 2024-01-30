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

import net.fabricmc.stitch.representation.JarClassEntry.ClassEntryPopulator;
import net.fabricmc.stitch.util.StitchUtil;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarInputStream;

public class JarReader
{
    private final JarRootEntry jar;

    public JarReader(JarRootEntry jar) {
        this.jar = jar;
    }

    public void apply() throws IOException {
        apply(new byte[] { });
    }

    public void apply(byte[] salt) throws IOException {
        // Stage 1: read .JAR class/field/method meta
        try (FileInputStream fileStream = new FileInputStream(jar.file)) {
            try (JarInputStream jarStream = new JarInputStream(fileStream)) {
                java.util.jar.JarEntry entry;

                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }

                    // really stupid fix for <=1.5.2!!!!!!!
                    if (entry.getName().matches(".*(paulscode|fasterxml|jcraft|javax).*")) {
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
                            startedAt = System.nanoTime();
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
                            fields.add(new JarFieldEntry(access, name, descriptor, signature, populator.name));

                            return super.visitField(access, name, descriptor, signature, value);
                        }

                        @Override
                        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                                         final String signature, final String[] exceptions) {
                            methods.add(new JarMethodEntry(access, name, descriptor, signature, populator.name));

                            return super.visitMethod(access, name, descriptor, signature, exceptions);
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

                            long timeSpan = (System.nanoTime() - startedAt) / 1000;
                            System.err.println("Loaded " + classEntry.getKey() + " in " + timeSpan + "μs");

                            super.visitEnd();
                        }
                    };
                    reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }

        System.err.println("Read " + this.jar.getAllClasses().size() + " (" + this.jar.getClasses().size() + ") classes.");

        // Stage 2: find subclasses
        this.jar.getAllClasses().forEach((c) -> c.populateParents(jar));
        System.err.println("Populated subclass entries.");

        // Stage 3: find inner classes
        this.jar.getAllClasses().forEach((c) -> c.populateInnerClasses(jar));
        System.err.println("Populated inner class entries.");

        // Stage 4: find classes in the same package
        this.jar.getClasses().forEach((c) -> c.populateSiblings(jar));
        System.err.println("Populated sibling class entries.");

        // Stage 5: hashing
        this.jar.hash(salt);
        System.err.println("Hashed jar entries.");

        System.err.println("- Done. -");
    }
}
