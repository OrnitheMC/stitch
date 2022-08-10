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

import com.google.common.io.ByteStreams;
import net.fabricmc.stitch.Main;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarRootEntry extends AbstractJarEntry
{
    final Object syncObject = new Object();
    final File file;
    final Map<String, JarClassEntry> classTree;
    final List<JarClassEntry> allClasses;
    final byte[] jarHash;

    public JarRootEntry(File file) throws IOException {
        super(file.getName());

        this.file = file;
        this.classTree = new TreeMap<>(Comparator.naturalOrder());
        this.allClasses = new ArrayList<>();

        long startedAt = System.nanoTime();
        try (var jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            JarEntry entry;

            while (entries.hasMoreElements()) {
                entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream inputStream = jarFile.getInputStream(entry)) {
                        Main.MESSAGE_DIGEST.update(ByteStreams.toByteArray(inputStream));
                    }
                }
            }
        }
        long timeSpan = (System.nanoTime() - startedAt) / 1000;
        System.err.println("Digested all classes within " + this.name + " in " + timeSpan + "μs");

        this.jarHash = Main.MESSAGE_DIGEST.digest();
    }

    public JarClassEntry getClass(String name, JarClassEntry.ClassEntryPopulator populator, boolean create) {
        if (name == null) {
            return null;
        }

        String[] nameSplit = name.split("\\$");
        int i = 0;

        JarClassEntry parent;
        JarClassEntry entry = classTree.get(nameSplit[i++]);
        if (entry == null && create) {
            entry = new JarClassEntry(nameSplit[0], nameSplit[0], populator, this);
            synchronized (syncObject) {
                allClasses.add(entry);
                classTree.put(entry.getName(), entry);
            }
        }

        StringBuilder fullyQualifiedBuilder = new StringBuilder(nameSplit[0]);

        while (i < nameSplit.length && entry != null) {
            fullyQualifiedBuilder.append('$');
            fullyQualifiedBuilder.append(nameSplit[i]);

            parent = entry;
            entry = entry.getInnerClass(nameSplit[i++]);

            if (entry == null && create) {
                entry = new JarClassEntry(nameSplit[i - 1], fullyQualifiedBuilder.toString(), populator, this);
                synchronized (syncObject) {
                    allClasses.add(entry);
                    parent.innerClasses.put(entry.getName(), entry);
                }
            }
        }

        return entry;
    }

    public Collection<JarClassEntry> getClasses() {
        return classTree.values();
    }

    public Collection<JarClassEntry> getAllClasses() {
        return Collections.unmodifiableList(allClasses);
    }

    @Override
    public byte[] getHash() {
        return jarHash;
    }
}
