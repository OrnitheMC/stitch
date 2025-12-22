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

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import net.fabricmc.stitch.util.StitchUtil;

public class MethodHierarchy {

    final String method;

    /**
     * hierarchies connected through specialized methods
     */
    final Set<MethodHierarchy> parents = StitchUtil.newIdentityHashSet();
    /**
     * hierarchies connected through bridge methods
     */
    final Set<MethodHierarchy> children = StitchUtil.newIdentityHashSet();

    final Map<JarClassEntry, JarMethodEntry> members = new IdentityHashMap<>();
    final Map<JarClassEntry, JarMethodEntry> sources = new IdentityHashMap<>();

    private boolean populated;
    private boolean fromLibrary;

    public MethodHierarchy(String method) {
        this.method = method;
    }

    public MethodHierarchy(JarClassEntry c, JarMethodEntry m) {
        this(m.getKey());

        this.members.put(c, m);
        this.sources.put(c, m);
    }

    void addMember(Classpath storage, JarClassEntry c, JarMethodEntry m) {
        members.put(c, m);
        m.hierarchy = this;

        fromLibrary |= !m.isMainJar(storage);
    }

    void populateSources(Classpath storage) {
        if (members.size() < 1) {
            sources.putAll(members);
        } else {
            for (Map.Entry<JarClassEntry, JarMethodEntry> e : members.entrySet()) {
                JarClassEntry c = e.getKey();
                JarMethodEntry m = e.getValue();

                populateSources(storage, c, c.isOneSideOnly() ? c.getSide() : m.getSide());
            }
        }
    }

    /**
     * @return true if the given class or one of its super classes is in the hierarchy
     */
    private boolean populateSources(Classpath storage, JarClassEntry c, Side side) {
        JarMethodEntry m = c.getMethod(method);

        boolean inHierarchy = false;
        boolean superInHierarchy = false;

        if (m != null) {
            if (Access.isPrivateOrStatic(m.getAccess())  || m.getName().charAt(0) == '<') {
                return false;
            }

            inHierarchy = members.containsKey(c);
        }

        JarClassEntry sup = c.getSuperClass(storage);
        if (sup != null) {
            superInHierarchy = populateSources(storage, sup, side) | superInHierarchy;
        }

        for (JarClassEntry itf : c.getInterfaces(storage)) {
            superInHierarchy = populateSources(storage, itf, side) | superInHierarchy;;
        }

        if (inHierarchy && !superInHierarchy) {
            if (c.isOneSideOnly() ? side.isIn(c.side) : side.isIn(m.side)) {
                sources.put(c, m);
            }
        }

        return inHierarchy || superInHierarchy;
    }

    void populateRelations(Classpath storage) {
        if (!populated) {
            for (Map.Entry<JarClassEntry, JarMethodEntry> e : members.entrySet()) {
                JarClassEntry c = e.getKey();
                JarMethodEntry m = e.getValue();

                JarMethodEntry b = m.getBridgeMethod(storage, c);
                JarMethodEntry s = m.getSpecializedMethod(storage, c);

                if (b != null) {
                    parents.add(b.hierarchy);
                }
                if (s != null) {
                    children.add(s.hierarchy);
                }
            }

            populated = true;

            // populate related hierarchies recursively...
            Set<MethodHierarchy> relations = StitchUtil.newIdentityHashSet();

            relations.addAll(parents);
            relations.addAll(children);

            for (MethodHierarchy relation : relations) {
                relation.populateRelations(storage);
            }

            // ... so that this flag can be correctly propagated
            if (parents.isEmpty() && fromLibrary) {
                for (MethodHierarchy hierarchy : getRelatedHierarchies()) {
                    hierarchy.fromLibrary = true;
                }
            }
        }
    }

    public Set<JarClassEntry> getClasses() {
        return members.keySet();
    }

    public Collection<JarMethodEntry> getMethods() {
        return members.values();
    }

    public Set<JarClassEntry> getSourceClasses() {
        return sources.keySet();
    }

    public Collection<JarMethodEntry> getSourceMethods() {
        return sources.values();
    }

    public Set<MethodHierarchy> getRelatedHierarchies() {
        Set<MethodHierarchy> hierarchies = StitchUtil.newIdentityHashSet();
        collectRelatedHierarchies(hierarchies);
        return hierarchies;
    }

    private void collectRelatedHierarchies(Set<MethodHierarchy> hierarchies) {
        if (hierarchies.add(this)) {
            for (MethodHierarchy parent : parents) {
                parent.collectRelatedHierarchies(hierarchies);
            }
            for (MethodHierarchy child : children) {
                child.collectRelatedHierarchies(hierarchies);
            }
        }
    }

    public Set<JarMethodEntry> getRelatedSourceMethods() {
        Set<JarMethodEntry> ms = StitchUtil.newIdentityHashSet();

        for (MethodHierarchy hierarchy : getRelatedHierarchies()) {
            ms.addAll(hierarchy.getSourceMethods());
        }

        return ms;
    }

    public boolean isSource(JarClassEntry c) {
        return sources.containsKey(c);
    }

    public boolean isOrigin(JarClassEntry c) {
        return parents.isEmpty() && sources.containsKey(c);
    }

    public boolean isFromMainJar() {
        return !fromLibrary;
    }
}
