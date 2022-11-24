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
    final Map<String, JarClassEntry> classTree;
    final List<JarClassEntry> allClasses;

    byte[] jarHash = { };

    public JarRootEntry(File file) throws IOException {
        super(file.getName(), "");

        this.file = file;
        this.classTree = new TreeMap<>(Comparator.naturalOrder());
        this.allClasses = new ArrayList<>();
    }

    public JarClassEntry getClass(String name, JarClassEntry.ClassEntryPopulator populator, boolean create) {
        if (name == null) {
            return null;
        }

        JarClassEntry entry = null;

        int i = name.lastIndexOf('$');
        String simpleName = (i > 0) ? name.substring(i + 1) : name;

        if (i > 0) {
            String enclName = name.substring(0, i);
            JarClassEntry ec = getClass(enclName, null, false);

            if (ec != null) {
                entry = ec.getInnerClass(simpleName);
            }
        } else {
            entry = classTree.get(name);
        }

        if (entry == null && create) {
            entry = new JarClassEntry(simpleName, name, populator, this);

            allClasses.add(entry);

            if (i < 0) {
                classTree.put(name, entry);
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
