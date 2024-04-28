/*
 * Copyright (c) 2024 FabricMC
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

package net.fabricmc.stitch.commands.tinyv2;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import net.fabricmc.mapping.util.EntryTriple;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.combine.IntermediarySplitter;
import net.fabricmc.stitch.combine.IntermediarySplitter.TriConsumer;
import net.fabricmc.stitch.combine.IntermediarySplitter.Writer;

public class CommandSplitTinyV2 extends Command {

    public CommandSplitTinyV2() {
        super("splitTinyV2");
    }

    @Override
    public String getHelpString() {
        return "<input> <output-client> <output-server>";
    }

    @Override
    public boolean isArgumentCountValid(int count) {
        return count == 3;
    }

    @Override
    public void run(String[] args) throws Exception {
        Path input = Paths.get(args[0]);
        Path outputC = Paths.get(args[1]);
        Path outputS = Paths.get(args[2]);

        if (Objects.equals(input, outputC) || Objects.equals(input, outputS) || Objects.equals(outputC, outputS)) {
            throw new IllegalArgumentException("input and output files cannot be the  same!");
        }

        if (args[0].isEmpty()  || "-".equals(args[0]) || !Files.exists(input)) {
            throw new RuntimeException("input cannot be empty!");
        }
        if (args[1].isEmpty() || "-".equals(args[1]) || (Files.exists(outputC) && !Files.isWritable(outputC))) {
            outputC = null;
        }
        if (args[2].isEmpty() || "-".equals(args[2]) || (Files.exists(outputS) && !Files.isWritable(outputS))) {
            outputS = null;
        }

        IntermediarySplitter splitter = new IntermediarySplitter();

        System.out.println("Reading " + input);
        splitter.read(input, this::readInput);

        splitter.write(outputC, outputS, new Writer() {

            private BufferedWriter writer;

            @Override
            public boolean open(Path path) throws IOException {
                if (path != null) {
                    System.out.println("Writing " + path);
                    writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);

                    writer().write("tiny");
                    writer().write("\t");
                    writer().write("2");;
                    writer().write("\t");
                    writer().write("0");
                    writer().write("\t");
                    writer().write("official");
                    writer().write("\t");
                    writer().write("intermediary");
                    writer().newLine();

                    return true;
                } else {
                    return false;
                }
            }

            @Override
            public void acceptClass(String cls, String target) throws IOException {
                writer().write("c");
                writer().write("\t");
                writer().write(cls);
                writer().write("\t");
                writer().write(target);
                writer().newLine();
            }

            @Override
            public void acceptField(String cls, String name, String desc, String target) throws IOException {
                writer().write("\t");
                writer().write("f");
                writer().write("\t");
                writer().write(desc);
                writer().write("\t");
                writer().write(name);
                writer().write("\t");
                writer().write(target);
                writer().newLine();
            }

            @Override
            public void acceptMethod(String cls, String name, String desc, String target) throws IOException {
                writer().write("\t");
                writer().write("m");
                writer().write("\t");
                writer().write(desc);
                writer().write("\t");
                writer().write(name);
                writer().write("\t");
                writer().write(target);
                writer().newLine();
            }

            @Override
            public void close() throws IOException {
                writer().close();
                writer = null;
            }

            private BufferedWriter writer() throws IOException {
                if (writer == null) {
                    throw new IOException("not writing!");
                }

                return writer;
            }
        });

        System.out.println("Done!");
    }

    private void readInput(Path input, TriConsumer<String, String, String> cls, TriConsumer<EntryTriple, String, String> fld, TriConsumer<EntryTriple, String, String> mtd) throws IOException {
        int lineNumber = 0;

        try (BufferedReader br = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String[] header = br.readLine().split("\t");
            lineNumber++;

            if (header.length < 5 || header.length > 6 || !header[0].trim().equals("tiny") || !header[1].trim().equals("2")) {
                throw new RuntimeException("Invalid header!");
            }

            String srcNs = header[3];

            int clientNs = -1;
            int serverNs = -1;

            if (!"intermediary".equals(srcNs)) {
                throw new RuntimeException("invalid src namespace! only 'intermediary' is supported!");
            }
            for (int i = 4; i < header.length; i++) {
                String dstNs = header[i];

                if ("clientOfficial".equals(dstNs)) {
                    clientNs = i - 3;
                } else if ("serverOfficial".equals(dstNs)) {
                    serverNs = i - 3;
                } else {
                    throw new RuntimeException("invalid dst namespace! only 'clientOfficial' or 'serverOfficial' is supported!");
                }
            }

            String line;
            int indents ;

            String lastCls = null;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                String[] parts = line.split("\t");

                for (indents = 0; indents < parts.length; indents++) {
                    if (!parts[indents].isEmpty()) {
                        break;
                    }
                }
                for (int i = indents; i < parts.length; i++) {
                    if (parts[i].isEmpty()) {
                        parts[i] = null;
                    }
                }

                switch (parts[indents]) {
                case "c": {
                    String name = parts[indents + 1];
                    String client = clientNs > 0 ? parts[indents + 1 + clientNs] : null;
                    String server = serverNs > 0 && parts.length == indents + 4 ? parts[indents + 1 + serverNs] : null;

                    cls.accept(name, client, server);

                    lastCls = name;

                    break;
                }
                case "f": {
                    String desc = parts[indents + 1];
                    String name = parts[indents + 2];
                    String client = clientNs > 0 ? parts[indents + 2 + clientNs] : null;
                    String server = serverNs > 0 && parts.length == indents + 5 ? parts[indents + 2 + serverNs] : null;

                    fld.accept(new EntryTriple(lastCls, name, desc), client, server);

                    break;
                }
                case "m": {
                    String desc = parts[indents + 1];
                    String name = parts[indents + 2];
                    String client = clientNs > 0 ? parts[indents + 2 + clientNs] : null;
                    String server = serverNs > 0 && parts.length == indents + 5 ? parts[indents + 2 + serverNs] : null;

                    mtd.accept(new EntryTriple(lastCls, name, desc), client, server);

                    break;
                }
                default:
                    throw new IllegalStateException("unsupported entry type " + parts[indents]);
                }
            }
        } catch (Throwable t) {
            throw new IOException("error reading tiny 2 file on line " + lineNumber, t);
        }
    }
}
