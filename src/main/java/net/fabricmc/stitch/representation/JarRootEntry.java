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

import java.io.File;
import java.io.IOException;
import java.util.*;

public class JarRootEntry extends AbstractJarEntry
{
    final File file;
    final boolean gameJar;
    final Set<String> classQueue;
    final Map<String, JarClassEntry> classTree;
    final Map<String, JarClassEntry> allClasses;

    public JarRootEntry(File file, boolean gameJar) throws IOException {
        super(file.getName(), "");

        this.file = file;
        this.gameJar = gameJar;
        this.classQueue = new LinkedHashSet<>();
        this.classTree = new TreeMap<>(Comparator.naturalOrder());
        this.allClasses = new TreeMap<>(Comparator.naturalOrder());
    }

    public boolean isGameJar() {
        return gameJar;
    }

    public JarClassEntry getClass(String name, JarClassEntry.ClassEntryPopulator populator) {
        if (name == null) {
            return null;
        }

        JarClassEntry entry = allClasses.get(name);

        if (entry == null && populator != null) {
            entry = new JarClassEntry(name, this);
            entry.populate(populator);

            allClasses.put(name, entry);
            if (!entry.hasDeclaringClass() && !entry.hasEnclosingClass()) {
                classTree.put(name, entry);
            }
        }

        return entry;
    }

    public Collection<JarClassEntry> getClasses() {
        return classTree.values();
    }

    public Collection<JarClassEntry> getAllClasses() {
        return allClasses.values();
    }

    @Override
    public void hash(byte[] salt) {
        super.hash(salt);

        for (JarClassEntry classEntry : allClasses.values()) {
            classEntry.hash(hash);
        }
    }
}
