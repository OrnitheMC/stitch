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

import net.fabricmc.stitch.util.StitchUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.jar.JarInputStream;

public class JarReader
{
    private final JarRootEntry jar;

    public JarReader(JarRootEntry jar) {
        this.jar = jar;
    }

    public void apply() throws IOException {
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
                    java.util.jar.JarEntry finalEntry = entry;
                    ClassVisitor visitor = new ClassVisitor(StitchUtil.ASM_VERSION, null)
                    {
                        private JarClassEntry classEntry;
                        private long startedAt;

                        @SuppressWarnings("deprecation")
                        @Override
                        public void visit(final int version, final int access, final String name, final String signature,
                                          final String superName, final String[] interfaces) {
                            startedAt = System.nanoTime();
                            byte[] bytes;
                            if (finalEntry.getSize() < reader.b.length) {
                                bytes = Arrays.copyOf(reader.b, (int) finalEntry.getSize());
                            } else {
                                bytes = reader.b;
                            }

                            var classEntry = new JarClassEntry.ClassEntryPopulator(access, signature, superName, interfaces, bytes);
                            this.classEntry = jar.getClass(name, classEntry, true);
                        }

                        @Override
                        public FieldVisitor visitField(final int access, final String name, final String descriptor,
                                                       final String signature, final Object value) {
                            JarFieldEntry field = new JarFieldEntry(access, name, descriptor, signature, this.classEntry);
                            this.classEntry.fields.put(field.getKey(), field);

                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                                         final String signature, final String[] exceptions) {
                            JarMethodEntry method = new JarMethodEntry(access, name, descriptor, signature, this.classEntry);
                            this.classEntry.methods.put(method.getKey(), method);

                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            long timeSpan = (System.nanoTime() - startedAt) / 1000;
                            System.err.println("Loaded " + this.classEntry.getName() + " in " + timeSpan + "μs");
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

        // Stage 3: join identical MethodEntries
        System.err.println("Joining MethodEntries...");
        Set<JarClassEntry> traversedClasses = StitchUtil.newIdentityHashSet();

        int joinedMethods = 1;
        int uniqueMethods = 0;

        Collection<JarMethodEntry> checkedMethods = StitchUtil.newIdentityHashSet();

        for (JarClassEntry entry : jar.getAllClasses()) {
            if (traversedClasses.contains(entry)) {
                continue;
            }

            ClassPropagationTree tree = new ClassPropagationTree(jar, entry);
            if (tree.getClasses().size() == 1) {
                traversedClasses.add(entry);
                continue;
            }

            for (JarClassEntry c : tree.getClasses()) {
                for (JarMethodEntry m : c.getMethods()) {
                    if (!checkedMethods.add(m)) {
                        continue;
                    }

                    // get all matching entries
                    List<JarClassEntry> mList = m.getMatchingEntries(jar, c);

                    if (mList.size() > 1) {
                        for (JarClassEntry key : mList) {
                            JarMethodEntry value = key.getMethod(m.getKey());
                            if (value != m) {
                                key.methods.put(m.getKey(), m);
                                joinedMethods++;
                            }
                        }
                    }
                }
            }

            traversedClasses.addAll(tree.getClasses());
        }

        System.err.println("Joined " + joinedMethods + " MethodEntries (" + uniqueMethods + " unique, " + traversedClasses.size() + " classes).");

        System.err.println("- Done. -");
    }
}
