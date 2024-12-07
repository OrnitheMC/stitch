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

import java.util.*;

public class JarMethodEntry extends AbstractJarEntry
{
    protected String desc;
    protected String signature;

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

    @Override
    protected String getKey() {
        return super.getKey() + desc;
    }

    @Override
    public boolean isSerializable(Classpath storage) {
        if (Access.isStatic(access) || Access.isPrivate(access)) {
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

    public boolean isSource(Classpath storage, JarClassEntry c) {
        if (Access.isPrivateOrStatic(getAccess())) {
            return true;
        }

        Set<JarClassEntry> entries = StitchUtil.newIdentityHashSet();
        entries.add(c);
        getMatchingSources(entries, storage, c);
        return entries.size() == 1;
    }

    public List<JarClassEntry> getMatchingEntries(Classpath storage, JarClassEntry c) {
        if (Access.isPrivateOrStatic(getAccess())) {
            return Collections.singletonList(c);
        }

        Set<JarClassEntry> entries = StitchUtil.newIdentityHashSet();
        Set<JarClassEntry> entriesNew = StitchUtil.newIdentityHashSet();
        entries.add(c);
        int lastSize = 0;

        while (entries.size() > lastSize) {
            lastSize = entries.size();

            for (JarClassEntry cc : entries) {
                getMatchingSources(entriesNew, storage, cc);
            }
            entries.addAll(entriesNew);
            entriesNew.clear();

            for (JarClassEntry cc : entries) {
                getMatchingEntries(entriesNew, storage, cc, 0);
            }
            entries.addAll(entriesNew);
            entriesNew.clear();
        }

        entries.removeIf(cc -> cc.getMethod(getKey()) == null);

        return new ArrayList<>(entries);
    }

    void getMatchingSources(Collection<JarClassEntry> entries, Classpath storage, JarClassEntry c) {
        JarMethodEntry m = c.getMethod(getKey());
        if (m != null) {
            if (!Access.isPrivateOrStatic(m.getAccess())) {
                entries.add(c);
            }
        }

        JarClassEntry superClass = c.getSuperClass(storage);
        if (superClass != null) {
            getMatchingSources(entries, storage, superClass);
        }

        for (JarClassEntry itf : c.getInterfaces(storage)) {
            getMatchingSources(entries, storage, itf);
        }
    }

    void getMatchingEntries(Collection<JarClassEntry> entries, Classpath storage, JarClassEntry c, int indent) {
        entries.add(c);

        for (JarClassEntry cc : c.getSubclasses(storage)) {
            getMatchingEntries(entries, storage, cc, indent + 1);
        }

        for (JarClassEntry cc : c.getImplementers(storage)) {
            getMatchingEntries(entries, storage, cc, indent + 1);
        }
    }
}