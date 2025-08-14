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

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class GenStateSplit extends GenState
{
    private final Map<AbstractJarEntry, String> clientValues = new IdentityHashMap<>();
    private final Map<AbstractJarEntry, String> serverValues = new IdentityHashMap<>();
    private final Map<JarMethodEntry, String> clientMethodNames = new IdentityHashMap<>();
    private final Map<JarMethodEntry, String> serverMethodNames = new IdentityHashMap<>();
    private GenMap clientOldToIntermediary, serverOldToIntermediary, clientNewToOld, serverNewToOld, clientToServer;

    public void generate(File file, Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld) throws IOException {
        File tmp = new File(file.getParentFile(), ".tmp");
        File client = new File(tmp, "client.tiny");
        File server = new File(tmp, "server.tiny");

        tmp.mkdirs();

        generate(client, server, storageClient, storageServer, storageClientOld, storageServerOld);

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

    public void generate(File clientFile, File serverFile, Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld) throws IOException {
        BufferedWriter cw = (clientFile == null) ? null : new BufferedWriter(new FileWriter(clientFile));
        BufferedWriter sw = (serverFile == null) ? null : new BufferedWriter(new FileWriter(serverFile));

        if (cw != null) cw.write("v1\tofficial\t" + targetNamespace + "\n");
        if (sw != null) sw.write("v1\tofficial\t" + targetNamespace + "\n");

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

        if (cw != null) cw.close();
        if (sw != null) sw.close();
    }

    private String nextName(AbstractJarEntry centry, AbstractJarEntry sentry) {
        if (sentry == null) {
            return nextName(clientValues, centry);
        }
        if (centry == null) {
            return nextName(serverValues, sentry);
        }

        String name = null;

        String cname = clientValues.get(centry);
        String sname = serverValues.get(sentry);

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
            name = genName(centry, sentry);

            clientValues.put(centry, name);
            serverValues.put(sentry, name);
        }

        return name;
    }

    private String nextMethodName(Classpath storageClient, Classpath storageServer, JarClassEntry cc, JarClassEntry sc, JarMethodEntry cm, JarMethodEntry sm) {
        if (sm == null) {
            return nextMethodName(clientValues, storageClient, cc, cm);
        }
        if (cm == null) {
            return nextMethodName(serverValues, storageServer, sc, sm);
        }

        String ckey = cm.getName() + cm.getDescriptor();
        String skey = sm.getName() + sm.getDescriptor();
        Set<JarMethodEntry> cms = new TreeSet<>((m1, m2) -> compareSourceMethods(storageClient, m1, m2));
        Set<JarMethodEntry> cns = propagateNames ? null : new TreeSet<>((m1, m2) -> compareSourceMethods(storageClient, m1, m2));
        Set<JarMethodEntry> sms = new TreeSet<>((m1, m2) -> compareSourceMethods(storageServer, m1, m2));
        Set<JarMethodEntry> sns = propagateNames ? null : new TreeSet<>((m1, m2) -> compareSourceMethods(storageServer, m1, m2));

        findSourceMethod(storageClient, cc, ckey, cms, cns);
        findSourceMethod(storageServer, sc, skey, sms, sns);

        if (cms.isEmpty() && sms.isEmpty()) {
            // method is most likely private or static
            return nextName(cm, sm);
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
                    name = nextName(cm, sm);
                }
            }
        }
        if (name == null) {
            boolean cmain = cpm.isMainJar(storageClient);
            boolean smain = spm.isMainJar(storageServer);
            if (cmain && smain) {
                // for methods from the main jars, do not propagate
                // names through bridge/specialized methods
                if (!propagateNames) {
                    cit = cns.iterator();
                    sit = sns.iterator();
                    cpm = cit.next();
                    spm = sit.next();
                }

                name = nextName(cpm, spm);
            } else {
                // for methods inherited from libraries or the JDK
                // propagate names through bridge/specialized methods
                if (!cmain && !smain) {
                    if (!cpm.getName().equals(spm.getName())) {
                        throw new RuntimeException("incompatible library method sources: client[" + cm + "] - server[" + sm + "]");
                    }
                }
                if (!cmain) {
                    name = cpm.getName();
                }
                if (!smain) {
                    name = spm.getName();
                }
            }
        }

        while (cit.hasNext()) {
            clientValues.put(cit.next(), name);
        }
        while (sit.hasNext()) {
            serverValues.put(sit.next(), name);
        }

        return name;
    }

    private void addField(BufferedWriter cw, BufferedWriter sw, Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld, JarClassEntry cc, JarClassEntry sc, JarFieldEntry cf, JarFieldEntry sf) throws IOException {
        String fName = getFieldName(storageClient, storageServer, storageClientOld, storageServerOld, cc, sc, cf, sf);
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
    private String getFieldName(Classpath storageClient, Classpath storageServer, Classpath storageClientOld, Classpath storageServerOld, JarClassEntry cc, JarClassEntry sc, JarFieldEntry cf, JarFieldEntry sf) {
        boolean clientUnmapped = (cf != null && !isMappedField(storageClient, cc, cf));
        boolean serverUnmapped = (sf != null && !isMappedField(storageServer, sc, sf));

        if (clientUnmapped && serverUnmapped) {
            return null;
        }
        if (clientUnmapped) {
            return sf == null ? null : cf.getName();
        }
        if (serverUnmapped) {
            return cf == null ? null : sf.getName();
        }

        String cname = (cf == null) ? null : inheritFieldName(storageClient, storageClientOld, cc, cf, clientNewToOld, clientOldToIntermediary);
        String sname = (sf == null) ? null : inheritFieldName(storageServer, storageServerOld, sc, sf, serverNewToOld, serverOldToIntermediary);

        if (cname != null && sname != null && !cname.equals(sname)) {
            throw new IllegalStateException("illegal name inheritance: client[" + cc.getName() + "." + cf.getName() + cf.getDescriptor() + " -> " + cname + "], server[" + sc.getName() + "." + sf.getName() + sf.getDescriptor() + " -> " + sname + "]");
        }
        if (cname != null) {
            return cname;
        }
        if (sname != null) {
            return sname;
        }

        String name = nextName(cf, sf);

        return name;
    }

    private String inheritFieldName(Classpath storage, Classpath storageOld, JarClassEntry c, JarFieldEntry f, GenMap newToOld, GenMap oldToIntermediary) {
        EntryTriple findEntry = newToOld.getField(c.getName(), f.getName(), f.getDescriptor());
        if (findEntry != null) {
            JarClassEntry oldClass = storageOld.getClass(findEntry.getOwner());
            JarFieldEntry oldField = (oldClass == null) ? null : oldClass.getField(findEntry.getName() + findEntry.getDesc());
            if (oldField != null && !isSerializable(storageOld, oldField)) {
                EntryTriple findIntermediaryEntry = oldToIntermediary.getField(findEntry);
                if (findIntermediaryEntry != null) {
                    return findIntermediaryEntry.getName();
                } else if (!isMappedFieldName(findEntry.getName())) {
                    return findEntry.getName();
                }
            }
        }
        return null;
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
                    String name = nameList.get(i - 1);
                    for (JarMethodEntry mm : allEntries) {
                        methodNames.put(mm, name);
                    }
                    System.out.println("OK! chose " + name);
                    return name;
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
        Set<JarMethodEntry> clientEntries = findNames(storageClient, storageClientOld, cc, cm, clientNewToOld, clientOldToIntermediary, clientNames);
        Set<JarMethodEntry> serverEntries = findNames(storageServer, storageServerOld, sc, sm, serverNewToOld, serverOldToIntermediary, serverNames);

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
            cname = (cm == null || cm.getName().charAt(0) == '<' || !cm.isSource(storageClient, cc) || isEnumMethod(storageClient, cc, cm)) ? null : cm.getName();
            sname = (sm == null || sm.getName().charAt(0) == '<' || !sm.isSource(storageServer, sc) || isEnumMethod(storageServer, sc, sm)) ? null : sm.getName();

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
            if (cm != null && cm.getName().charAt(0) != '<' && cm.isSource(storageClient, cc) && !isEnumMethod(storageClient, cc, cm))
                cw.write("METHOD\t" + cc.getName() + "\t" + cm.getDescriptor() + "\t" + cm.getName() + "\t" + mName + "\n");
            if (sm != null && sm.getName().charAt(0) != '<' && sm.isSource(storageServer, sc) && !isEnumMethod(storageServer, sc, sm))
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

        boolean cunobf = clientName != null && (isSerializable(storageClient, cc) || ((cc.isInner() || cc.isLocal()) ? !isObfuscated(cc.getInnerName()) : (!cc.isAnonymous() && clientName.indexOf('$') < 0 && !isObfuscated(clientName))));
        boolean sunobf = serverName != null && (isSerializable(storageServer, sc) || ((sc.isInner() || sc.isLocal()) ? !isObfuscated(sc.getInnerName()) : (!sc.isAnonymous() && serverName.indexOf('$') < 0 && !isObfuscated(serverName))));

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

                Pair<String, String> icname = (cc == null) ? null : inheritClassName(clientName, storageClient, storageClientOld, clientNewToOld, clientOldToIntermediary);
                Pair<String, String> isname = (sc == null) ? null : inheritClassName(serverName, storageServer, storageServerOld, serverNewToOld, serverOldToIntermediary);

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
                    iname = nextName(cc, sc);
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

                addField(cw, sw, storageClient, storageServer, storageClientOld, storageServerOld, cc, sc, cf, sf);
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
                    addField(cw, sw, null, storageServer, null, storageServerOld, null, sc, null, sf);
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

    private Pair<String, String> inheritClassName(String fullName, Classpath storage, Classpath storageOld, GenMap newToOld, GenMap oldToIntermediary) {
        String packageName = null;
        String cname = null;

        String findName = newToOld.getClass(fullName);
        if (findName != null) {
            String oldName = findName;
            findName = oldToIntermediary.getClass(findName);
            if (findName != null) {
                // similar to above, the names we generate follow the convention for inner classes
                String[] nr = fullName.split("\\$");
                String[] or = findName.split("\\$");
                if (or.length > 1) {
                    cname = stripLocalClassPrefix(or[or.length - 1]);
                } else {
                    cname = stripPackageName(findName);
                    if (nr.length == 1 && !cname.startsWith("C_")) {
                        // not a name we generated, thus an unobfuscated name!
                        // then we inherit not just the name but the package too
                        packageName = findName.substring(0, findName.length() - cname.length());
                    }
                }
                JarClassEntry oldEntry = storageOld.getClass(oldName);
                if (oldEntry != null && isSerializable(storageOld, oldEntry)) {
                    cname = null;
                }
            }
        }

        return cname == null ? null : Pair.of(packageName, cname);
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

        boolean splitClient = clientMappings != null && needsSplitting(clientMappings);
        boolean splitServer = serverMappings != null && needsSplitting(serverMappings);

        if (splitClient) {
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
        } else {
            client = clientMappings;
        }
        if (splitServer) {
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
        } else {
            server = serverMappings;
        }

        prepareUpdateFromSplitInternal(client, server, clientMatches, serverMatches, clientServerMatches, invertClientMatches, invertServerMatches, invertClientServerMatches);

        if (splitClient) {
            client.delete();
            tmp.delete();
        }
        if (splitServer) {
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

    private boolean needsSplitting(File mappings) {
        try (BufferedReader br = new BufferedReader(new FileReader(mappings))) {
            String header = br.readLine();

            if (header != null) {
                return !header.startsWith("v1\tofficial");
            }
        } catch (IOException e) {
        }

        return false;
    }
}
