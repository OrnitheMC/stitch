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

package net.fabricmc.stitch.commands;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.MatcherUtil;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public class GenStateSplit extends GenState
{
    final Map<JarMethodEntry, String> clientMethodNames = new IdentityHashMap<>();
    final Map<JarMethodEntry, String> serverMethodNames = new IdentityHashMap<>();
    private GenMap clientOldToIntermediary = new GenMap(), serverOldToIntermediary = new GenMap(), clientNewToOld = new GenMap(), serverNewToOld = new GenMap(), clientToServer = new GenMap();

    public void generate(File file, Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld) throws IOException {
        File tmp = new File(file.getParentFile(), ".tmp");
        File client = new File(tmp, "client.tiny");
        File server = new File(tmp, "server.tiny");

        tmp.mkdirs();

        BufferedWriter cw = new BufferedWriter(new FileWriter(client));
        BufferedWriter sw = new BufferedWriter(new FileWriter(server));

        cw.write("v1\tofficial\t" + targetNamespace + "\n");
        sw.write("v1\tofficial\t" + targetNamespace + "\n");

        // hack to make sure we don't write them multiple times
        Set<String> serverClasses = new HashSet<>();

        if (storageClient != null) {
            for (JarClassEntry cc : storageClient.getJar().getClasses()) {
                JarClassEntry sc = null;

                if (storageServer != null) {
                    String serverName = clientToServer.getClass(cc.getName());
                    if (serverName != null) {
                        sc = storageServer.getClass(serverName);
                        serverClasses.add(serverName);
                    }
                }

                addClass(cw, sw, storageClient, storageServer, storageClientOld, storageServerOld, cc, sc, this.defaultPackage);
            }
        }
        if (storageServer != null) {
            for (JarClassEntry sc : storageServer.getJar().getClasses()) {
                if (!serverClasses.contains(sc.getName())) {
                    addClass(cw, sw, storageClient, storageServer, storageClientOld, storageServerOld, null, sc, this.defaultPackage);
                }
            }
        }

        serverClasses.clear();

        cw.close();
        sw.close();

        try {
            new CommandCombineTiny().run(new String[] {
                client.getAbsolutePath(),
                server.getAbsolutePath(),
                file.getAbsolutePath()
            });
        } catch (Exception e) {
            throw new IOException(e);
        }
        

        client.delete();
        server.delete();
        tmp.delete();
    }

    private String next(AbstractJarEntry centry, AbstractJarEntry sentry, String prefix) {
        if (centry == null) {
            return next(sentry, prefix);
        }
        if (sentry == null) {
            return next(centry, prefix);
        }

        String name = null;

        String cname = values.get(centry);
        String sname = values.get(sentry);

        if (cname != null && sname != null && !cname.equals(sname)) {
            throw new RuntimeException("conflict in names generated for client and server entry: (" + centry + ", " + sentry + ") -> (" + cname + ", " + sname + ")");
        }
        if (cname != null) {
            name = cname;
        }
        if (sname != null) {
            name = sname;
        }

        if (name == null) {
            BigInteger cint = new BigInteger(centry.getHash());
            BigInteger sint = new BigInteger(sentry.getHash());
            BigInteger bigInt = cint.multiply(sint);
            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < nameLength; i++) {
                int digit = bigInt.mod(BigInteger.valueOf(10)).intValue();
                bigInt = bigInt.divide(BigInteger.valueOf(10));

                builder.insert(0, (char) ('0' + digit));
            }

            name = builder.toString();

            values.put(centry, name);
            values.put(sentry, name);
        }

        return prefix + "_" + name;
    }

    private String nextMethodName(Classpath storageClient, Classpath storageServer, JarClassEntry cc, JarClassEntry sc, JarMethodEntry cm, JarMethodEntry sm) {
        if (cm == null) {
            return nextMethodName(storageServer, sc, sm);
        }
        if (sm == null) {
            return nextMethodName(storageClient, cc, cm);
        }

        String ckey = cm.getName() + cm.getDescriptor();
        String skey = sm.getName() + sm.getDescriptor();
        Comparator<JarMethodEntry> comp = (m1, m2) -> {
            return m1.getParentName().compareTo(m2.getParentName());
        };
        Set<JarMethodEntry> cms = new TreeSet<>(comp);
        Set<JarMethodEntry> sms = new TreeSet<>(comp);

        findSourceMethod(storageClient, cc, ckey, cms);
        findSourceMethod(storageServer, sc, skey, sms);

        if (cms.isEmpty() && sms.isEmpty()) {
            // method is most likely private or static
            return next(cm, sm, "m");
        }
        if (cms.isEmpty() || sms.isEmpty()) {
            throw new RuntimeException("incompatible method inheritance: client[" + cm + "](" + String.join(", ", cms.stream().map(Object::toString).collect(Collectors.toList())) + ") - server[" + sm + "](" + String.join(", ", sms.stream().map(Object::toString).collect(Collectors.toList())) + ")");
        }

        Iterator<JarMethodEntry> cit = cms.iterator();
        Iterator<JarMethodEntry> sit = sms.iterator();
        JarMethodEntry cpm = cit.next();
        JarMethodEntry spm = sit.next();

        String name = null;

        EntryTriple findEntry = clientToServer.getMethod(cpm.getParentName(), cpm.getName(), cpm.getDescriptor());
        if (findEntry != null) {
            JarClassEntry findCls = storageServer.getClass(findEntry.getOwner());
            if (findCls != null) {
                JarMethodEntry findMtd = findCls.getMethod(findEntry.getName() + findEntry.getDesc());
                if (findMtd != spm) {
//                    throw new RuntimeException("incompatible method sources (server -> src)[" + sm + " -> " + spm + "] and matches (client -> server)[" + cpm + " -> " + findMtd + "]");
                    name = next(cm, sm, "m");
                }
            }
        }
        if (name == null) {
            name = next(cpm, spm, "m");
        }

        String suf = name.substring(2);

        while (cit.hasNext()) {
            values.put(cit.next(), suf);
        }
        while (sit.hasNext()) {
            values.put(sit.next(), suf);
        }

        return name;
    }

    private void addField(BufferedWriter cw, BufferedWriter sw, JarClassEntry cc, JarClassEntry sc, JarFieldEntry cf, JarFieldEntry sf) throws IOException {
        String fName = getFieldName(cc, sc, cf, sf);
        if (fName == null) {
            String cname = (cf == null) ? null : cf.getName();
            String sname = (sf == null) ? null : sf.getName();

            if (cname != null && sname != null && !cname.equals(sname)) {
                throw new RuntimeException("conflicting unmapped field names: client[" + cc.getName() + "." + cf.getName() + cf.getDescriptor() + " -> " + cname + "], server[" + sc.getName() + "." + sf.getName() + sf.getDescriptor() + " -> " + sname + "]");
            }
            if (cname != null) {
                fName = cname;
            }
            if (sname != null) {
                fName = cname;
            }
        }

        if (fName != null) {
            if (cf != null) cw.write("FIELD\t" + cc.getName() + "\t" + cf.getDescriptor() + "\t" + cf.getName() + "\t" + fName + "\n");
            if (sf != null) sw.write("FIELD\t" + sc.getName() + "\t" + sf.getDescriptor() + "\t" + sf.getName() + "\t" + fName + "\n");
        }
    }


    @Nullable
    private String getFieldName(JarClassEntry cc, JarClassEntry sc, JarFieldEntry cf, JarFieldEntry sf) {
        boolean clientUnmapped = (cf != null && !isMappedField(cf));
        boolean serverUnmapped = (sf != null && !isMappedField(sf));

        if (clientUnmapped && serverUnmapped) {
            return null;
        }
        if (clientUnmapped) {
            return sf == null ? null : cf.getName();
        }
        if (serverUnmapped) {
            return cf == null ? null : sf.getName();
        }

        String cname = (cf == null) ? null : inheritFieldName(cc, cf, clientNewToOld, clientOldToIntermediary);
        String sname = (sf == null) ? null : inheritFieldName(sc, sf, serverNewToOld, serverOldToIntermediary);

        if (cname != null && sname != null && !cname.equals(sname)) {
            throw new IllegalStateException("illegal name inheritance: client[" + cc.getName() + "." + cf.getName() + cf.getDescriptor() + " -> " + cname + "], server[" + sc.getName() + "." + sf.getName() + sf.getDescriptor() + " -> " + sname + "]");
        }
        if (cname != null) {
            return cname;
        }
        if (sname != null) {
            return sname;
        }

        String name = next(cf, sf, "f");

        return name;
    }

    private String inheritFieldName(JarClassEntry c, JarFieldEntry f, GenMap newToOld, GenMap oldToIntermediary) {
        //noinspection deprecation
        EntryTriple findEntry = newToOld.getField(c.getName(), f.getName(), f.getDescriptor());
        if (findEntry != null) {
            EntryTriple findIntermediaryEntry = oldToIntermediary.getField(findEntry);
            if (findIntermediaryEntry != null) {
                return findIntermediaryEntry.getName();
            } else if (!isMappedFieldName(findEntry.getName())) {
                return findEntry.getName();
            }
        }

        return null;
    }

    private Set<JarMethodEntry> findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, GenMap newToOld, GenMap oldToIntermediary) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storage, storageOld, c, m, names, allEntries, newToOld, oldToIntermediary);
        return allEntries;
    }

    private void findNames(Classpath storage, Classpath storageOld, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods, GenMap newToOld, GenMap oldToIntermediary) {
        if (m == null || !usedMethods.add(m)) {
            return;
        }

        String suffix = "." + m.getName() + m.getDescriptor();

        if ((m.getAccess() & Opcodes.ACC_BRIDGE) != 0) {
            suffix += "(bridge)";
        }

        List<JarClassEntry> ccList = m.getMatchingEntries(storage, c);

        for (JarClassEntry cc : ccList) {
            if (newToOld != null) {
                EntryTriple findEntry = newToOld.getMethod(cc.getName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    EntryTriple oldEntry = findEntry;
                    findEntry = oldToIntermediary.getMethod(oldEntry);
                    if (findEntry != null) {
                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                    } else {
                        if (!isMappedMethodName(oldEntry.getName())) {
                            names.computeIfAbsent(oldEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storage, cc) + suffix);
                        } else {
                            // more involved...
                            JarClassEntry oldBase = storageOld.getClass(oldEntry.getOwner());
                            if (oldBase != null) {
                                JarMethodEntry oldM = oldBase.getMethod(oldEntry.getName() + oldEntry.getDesc());
                                List<JarClassEntry> cccList = oldM.getMatchingEntries(storageOld, oldBase);

                                for (JarClassEntry ccc : cccList) {
                                    findEntry = oldToIntermediary.getMethod(ccc.getName(), oldM.getName(), oldM.getDescriptor());
                                    if (findEntry != null) {
                                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageOld, ccc) + suffix);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(storage, storageOld, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods, newToOld, oldToIntermediary);
            }
        }
    }

    private String handleMethodConflicts(String side, Map<JarMethodEntry, String> methodNames, Map<String, Set<String>> names, Set<JarMethodEntry> allEntries) {
        for (JarMethodEntry mm : allEntries) {
            if (methodNames.containsKey(mm)) {
                return methodNames.get(mm);
            }
        }

        if (names.size() > 1) {
            System.out.println(side + " Conflict detected - matched same target name!");
            List<String> nameList = new ArrayList<>(names.keySet());
            Collections.sort(nameList);

            for (int i = 0; i < nameList.size(); i++) {
                String s = nameList.get(i);
                System.out.println((i+1) + ") " + s + " <- " + StitchUtil.join(", ", names.get(s)));
            }

            boolean interactive = true;
            if (!interactive) {
                throw new RuntimeException("Conflict detected!");
            }

            while (true) {
                String cmd = scanner.nextLine();
                int i;
                try {
                    i = Integer.parseInt(cmd);
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                    continue;
                }

                if (i >= 1 && i <= nameList.size()) {
                    for (JarMethodEntry mm : allEntries) {
                        methodNames.put(mm, nameList.get(i - 1));
                    }
                    System.out.println("OK!");
                    return nameList.get(i - 1);
                }
            }
        } else if (names.size() == 1) {
            String s = names.keySet().iterator().next();
            for (JarMethodEntry mm : allEntries) {
                methodNames.put(mm, s);
            }
            return s;
        }

        return null;
    }

    @Nullable
    private String getMethodName(Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld, JarClassEntry cc, JarClassEntry sc, JarMethodEntry cm, JarMethodEntry sm) {
        boolean cunmapped = (cm == null || !isMappedMethod(storageClient, cc, cm));
        boolean sunmapped = (sm == null || !isMappedMethod(storageServer, sc, sm));

        if (cunmapped && sunmapped) {
            return null;
        }

        if (cm != null && !cunmapped && clientMethodNames.containsKey(cm)) {
            return clientMethodNames.get(cm);
        }
        if (sm != null && !sunmapped && serverMethodNames.containsKey(sm)) {
            return serverMethodNames.get(sm);
        }

        Map<String, Set<String>> clientNames = new HashMap<>();
        Map<String, Set<String>> serverNames = new HashMap<>();
        Set<JarMethodEntry> clientEntries = findNames(storageClient, storageClientOld, cc, cm, clientNames, clientNewToOld, clientOldToIntermediary);
        Set<JarMethodEntry> serverEntries = findNames(storageServer, storageServerOld, sc, sm, serverNames, serverNewToOld, serverOldToIntermediary);

        String cname = (cm == null) ? null : handleMethodConflicts("[client]", clientMethodNames, clientNames, clientEntries);
        String sname = (sm == null) ? null : handleMethodConflicts("[server]", serverMethodNames, serverNames, serverEntries);

        if (cname != null && sname != null && !cname.equals(sname)) {
            throw new IllegalStateException("illegal name inheritance: client[" + cc.getName() + "." + cm.getName() + cm.getDescriptor() + " -> " + cname + "], server[" + sc.getName() + "." + sm.getName() + sm.getDescriptor() + " -> " + sname + "]");
        }
        if (cname != null) {
            return cname;
        }
        if (sname != null) {
            return sname;
        }

        String name = nextMethodName(storageClient, storageServer, cc, sc, cm, sm);

        for (JarMethodEntry m : clientEntries) {
            clientMethodNames.put(m, name);
        }
        for (JarMethodEntry m : serverEntries) {
            serverMethodNames.put(m, name);
        }

        return name;
    }

    private void addMethod(BufferedWriter cw, BufferedWriter sw, Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld, JarClassEntry cc, JarClassEntry sc, JarMethodEntry cm, JarMethodEntry sm) throws IOException {
        String mName = getMethodName(storageClient, storageServer, storageClientOld, storageServerOld, cc, sc, cm, sm);
        String cname = null;
        String sname = null;
        if (mName == null) {
            cname = (cm == null || cm.getName().startsWith("<") || !cm.isSource(storageClient, cc) || isEnumMethod(storageClient, cc, cm)) ? null : cm.getName();
            sname = (sm == null || sm.getName().startsWith("<") || !sm.isSource(storageServer, sc) || isEnumMethod(storageServer, sc, sm)) ? null : sm.getName();

            if (cname != null && sname != null && !cname.equals(sname)) {
                throw new RuntimeException("conflicting unmapped method names: client[" + cc.getName() + "." + cm.getName() + cm.getDescriptor() + " -> " + cname + "], server[" + sc.getName() + "." + sm.getName() + sm.getDescriptor() + " -> " + sname + "]");
            }
            if (cname != null) {
                mName = cname;
            }
            if (sname != null) {
                mName = sname;
            }
        }

        if (mName != null) {
            if (cm != null && isMappedMethod(storageClient, cc, cm))
                cw.write("METHOD\t" + cc.getName() + "\t" + cm.getDescriptor() + "\t" + cm.getName() + "\t" + mName + "\n");
            if (sm != null && isMappedMethod(storageServer, sc, sm))
                sw.write("METHOD\t" + sc.getName() + "\t" + sm.getDescriptor() + "\t" + sm.getName() + "\t" + mName + "\n");
        }
    }

    private void addClass(BufferedWriter cw, BufferedWriter sw, Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld, JarClassEntry cc, JarClassEntry sc, String translatedPrefix) throws IOException {
        boolean cisMc = (cc != null) && isMinecraftClass(cc);
        boolean sisMc = (sc != null) && isMinecraftClass(sc);
        if ((cisMc && !sisMc && sc != null) || (!cisMc && sisMc && cc != null)) {
            throw new RuntimeException("a Minecraft class was matched to a non Minecraft class! client: " + cc.getName() + ", server: " + sc.getName());
        }
        if ((cc == null || !cisMc) && (sc == null || !sisMc)) {
            return;
        }

        String clientName = (cc == null) ? null : cc.getName();
        String serverName = (sc == null) ? null : sc.getName();
        String iname = "";
        if ((cc != null && (cc.hasDeclaringClass() || cc.hasEnclosingClass())) || (sc != null && (sc.hasDeclaringClass() || sc.hasEnclosingClass()))) {
            throw new RuntimeException("generating intermediary for split jars with inner class attributes is not supported at this time!");
        }

        boolean cunobf = clientName != null && ((cc.isInner() || cc.isLocal()) ? !isObfuscated(cc.getInnerName()) : (!cc.isAnonymous() && clientName.indexOf('$') < 0 && !isObfuscated(clientName)));
        boolean sunobf = serverName != null && ((sc.isInner() || sc.isLocal()) ? !isObfuscated(sc.getInnerName()) : (!sc.isAnonymous() && serverName.indexOf('$') < 0 && !isObfuscated(serverName)));

        if (cunobf || sunobf) {
            if (cunobf && sunobf && !clientName.equals(serverName)) {
                throw new RuntimeException("conflicting unobfuscated class names (client, server) -> new: (" + clientName + ", " + serverName + ")");
            }
            if (cunobf) {
                translatedPrefix = clientName;
            }
            if (sunobf) {
                translatedPrefix = serverName;
            }
        } else {
            if ((cc != null && !isMappedClass(cc)) || (sc != null && !isMappedClass(sc))) {
                if ((clientName != null && !isMappedClass(cc)) && (serverName != null && !isMappedClass(sc)) && !clientName.equals(serverName)) {
                    throw new RuntimeException("conflicting unmapped class names (client, server) -> new: (" + clientName + ", " + serverName + ")");
                }
                if (cc != null) {
                    // throw exception in case the impl of isMappedClass
                    // changes but we forget to deal with it here
                    throw new IllegalStateException("don't know what to do with client class " + clientName);
                }
                if (sc != null) {
                    // throw exception in case the impl of isMappedClass
                    // changes but we forget to deal with it here
                    throw new IllegalStateException("don't know what to do with server class " + serverName);
                }
            } else {
                iname = null;

                Pair<String, String> icname = (cc == null) ? null : inheritClassName(clientName, clientNewToOld, clientOldToIntermediary);
                Pair<String, String> isname = (sc == null) ? null : inheritClassName(serverName, serverNewToOld, serverOldToIntermediary);

                if (icname != null && isname != null && !icname.equals(isname)) {
                    throw new IllegalStateException("illegal name inheritance: client[" + clientName + " -> " + (icname.getLeft() == null ? "" : icname.getLeft()) + icname.getRight() + "], server[" + serverName + " -> " + (isname.getLeft() == null ? "" : isname.getLeft()) + isname.getRight() + "]");
                }
                if (icname != null) {
                    iname = icname.getRight();
                    if (icname.getLeft() != null) {
                        translatedPrefix = icname.getLeft();
                    }
                }
                if (isname != null) {
                    iname = isname.getRight();
                    if (isname.getLeft() != null) {
                        translatedPrefix = isname.getLeft();
                    }
                }

                if (iname == null) {
                    iname = next(cc, sc, "C");
                }
            }
        }

        if (cc != null) cw.write("CLASS\t" + cc.getName() + "\t" + translatedPrefix + iname + "\n");
        if (sc != null) sw.write("CLASS\t" + sc.getName() + "\t" + translatedPrefix + iname + "\n");

        // hack to make sure we don't write them multiple times
        Set<String> serverFields = new HashSet<>();
        Set<String> serverMethods = new HashSet<>();

        if (cc != null) {
            for (JarFieldEntry cf : cc.getFields()) {
                JarFieldEntry sf = null;

                if (sc != null) {
                    EntryTriple key = clientToServer.getField(cc.getName(), cf.getName(), cf.getDescriptor());
                    if (key != null) {
                        sf = sc.getField(key.getName() + key.getDesc());
                        serverFields.add(sf.getName() + sf.getDescriptor());
                    }
                }

                addField(cw, sw, cc, sc, cf, sf);
            }
            for (JarMethodEntry cm : cc.getMethods()) {
                JarMethodEntry sm = null;

                if (sc != null) {
                    EntryTriple key = clientToServer.getMethod(cc.getName(), cm.getName(), cm.getDescriptor());
                    if (key != null) {
                        sm = sc.getMethod(key.getName() + key.getDesc());
                        serverMethods.add(sm.getName() + sm.getDescriptor());
                    }
                }

                addMethod(cw, sw, storageClient, storageServer, storageClientOld, storageServerOld, cc, sc, cm, sm);
            }
        }
        if (sc != null) {
            for (JarFieldEntry sf : sc.getFields()) {
                if (!serverFields.contains(sf.getName() + sf.getDescriptor())) {
                    addField(cw, sw, null, sc, null, sf);
                }
            }
            for (JarMethodEntry sm : sc.getMethods()) {
                if (!serverMethods.contains(sm.getName() + sm.getDescriptor())) {
                    addMethod(cw, sw, storageClient, storageServer, storageClientOld, storageServerOld, null, sc, null, sm);
                }
            }
        }

        // TODO: support inner classes
        //for (JarClassEntry ic : cc.getInnerClasses()) {
        //    addClass(writer, storageClient, storageServer, storageClientOld, storageServerOld, ic, ..., translatedPrefix + iname + "$");
        //}
    }

    private Pair<String, String> inheritClassName(String fullName, GenMap newToOld, GenMap oldToIntermediary) {
        String findName = newToOld.getClass(fullName);
        if (findName != null) {
            // similar to above, the names we generate follow the convention for inner classes
            findName = oldToIntermediary.getClass(findName);
            if (findName != null) {
                String[] nr = fullName.split("\\$");
                String[] or = findName.split("\\$");
                if (or.length > 1) {
                    return Pair.of(null, stripLocalClassPrefix(or[or.length - 1]));
                } else {
                    String cname = stripPackageName(findName);
                    if (cname.startsWith("C_")) {
                        return Pair.of(null, cname);
                    } else {
                        // not a name we generated, thus an unobfuscated name!
                        // then we inherit not just the name but the package too
                        return Pair.of(findName.substring(0, findName.length() - cname.length()), cname);
                    }
                }
            }
        }

        return null;
    }

    public void prepareUpdateFromMerged(File mappings, File clientMatches, File serverMatches, File clientServerMatches, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches) throws IOException {
        GenMap oldToIntermediary = new GenMap();

        try (FileInputStream inputStream = new FileInputStream(mappings)) {
            //noinspection deprecation
            oldToIntermediary.load(
                MappingsProvider.readTinyMappings(inputStream),
                "official",
                targetNamespace
            );
        }

        if (clientMatches != null) {
            clientNewToOld = new GenMap();
            clientOldToIntermediary = oldToIntermediary;

            try (BufferedReader reader = new BufferedReader(new FileReader(clientMatches))) {
                MatcherUtil.read(reader, !invertClientMatches, clientNewToOld::addClass, clientNewToOld::addField, clientNewToOld::addMethod);
            }
        }
        if (serverMatches != null) {
            serverNewToOld = new GenMap();
            serverOldToIntermediary = oldToIntermediary;

            try (BufferedReader reader = new BufferedReader(new FileReader(serverMatches))) {
                MatcherUtil.read(reader, !invertServerMatches, serverNewToOld::addClass, serverNewToOld::addField, serverNewToOld::addMethod);
            }
        }
        if (clientServerMatches != null) {
            clientToServer = new GenMap();

            try (BufferedReader reader = new BufferedReader(new FileReader(clientServerMatches))) {
                MatcherUtil.read(reader, invertClientServerMatches, clientToServer::addClass, clientToServer::addField, clientToServer::addMethod);
            }
        }
    }

    public void prepareUpdateFromSplit(File mappings, File clientMatches, File serverMatches, File clientServerMatches, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches) throws IOException {
        File tmp = new File(mappings.getParentFile(), ".tmp");
        File client = new File(tmp, "client.tiny");
        File server = new File(tmp, "server.tiny");

        tmp.mkdirs();

        try {
            new CommandSplitTiny().run(new String[] {
                mappings.getAbsolutePath(),
                client.getAbsolutePath(),
                server.getAbsolutePath()
            });
        } catch (Exception e) {
            throw new IOException(e);
        }

        prepareUpdateFromSplitInternal(client, server, clientMatches, serverMatches, clientServerMatches, invertClientMatches, invertServerMatches, invertClientServerMatches);

        client.delete();
        server.delete();
        tmp.delete();
    }

    public void prepareUpdateFromSplit(File clientMappings, File serverMappings, File clientMatches, File serverMatches, File clientServerMatches, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches) throws IOException {
        File tmp = null;
        File client = null;
        File server = null;

        if (clientMappings != null) {
            tmp = new File(clientMappings.getParentFile(), ".tmp");
            client = new File(tmp, "client.tiny");

            tmp.mkdirs();

            try {
                new CommandSplitTiny().run(new String[] {
                    clientMappings.getAbsolutePath(),
                    client.getAbsolutePath(),
                    "-"
                });
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        if (serverMappings != null) {
            tmp = new File(serverMappings.getParentFile(), ".tmp");
            server = new File(tmp, "server.tiny");

            tmp.mkdirs();

            try {
                new CommandSplitTiny().run(new String[] {
                    serverMappings.getAbsolutePath(),
                    "-",
                    server.getAbsolutePath()
                });
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        prepareUpdateFromSplitInternal(client, server, clientMatches, serverMatches, clientServerMatches, invertClientMatches, invertServerMatches, invertClientServerMatches);

        if (clientMappings != null) {
            client.delete();
            tmp.delete();
        }
        if (serverMappings != null) {
            server.delete();
            tmp.delete();
        }
    }

    public void prepareUpdateFromSplitInternal(File clientMappings, File serverMappings, File clientMatches, File serverMatches, File clientServerMatches, boolean invertClientMatches, boolean invertServerMatches, boolean invertClientServerMatches) throws IOException {
        if (clientMatches != null) {
            clientNewToOld = new GenMap();
            clientOldToIntermediary = new GenMap();

            try (FileInputStream inputStream = new FileInputStream(clientMappings)) {
                //noinspection deprecation
                clientOldToIntermediary.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    targetNamespace
                );
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(clientMatches))) {
                MatcherUtil.read(reader, !invertClientMatches, clientNewToOld::addClass, clientNewToOld::addField, clientNewToOld::addMethod);
            }
        }
        if (serverMatches != null) {
            serverNewToOld = new GenMap();
            serverOldToIntermediary = new GenMap();

            try (FileInputStream inputStream = new FileInputStream(serverMappings)) {
                //noinspection deprecation
                serverOldToIntermediary.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    targetNamespace
                );
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(serverMatches))) {
                MatcherUtil.read(reader, !invertServerMatches, serverNewToOld::addClass, serverNewToOld::addField, serverNewToOld::addMethod);
            }
        }
        if (clientServerMatches != null) {
            clientToServer = new GenMap();

            try (BufferedReader reader = new BufferedReader(new FileReader(clientServerMatches))) {
                MatcherUtil.read(reader, invertClientServerMatches, clientToServer::addClass, clientToServer::addField, clientToServer::addMethod);
            }
        }
    }
}
