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

package net.fabricmc.stitch.commands;

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

public class CommandSplitTiny extends Command {

    public CommandSplitTiny() {
        super("splitTiny");
    }

    @Override
    public String getHelpString() {
        return "<input> <output-client> <output server>";
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

        if (args[0].isBlank()  || "-".equals(args[0]) || !Files.exists(input)) {
            throw new RuntimeException("input cannot be empty!");
        }
        if (args[1].isBlank() || "-".equals(args[1]) || (Files.exists(outputC) && !Files.isWritable(outputC))) {
            outputC = null;
        }
        if (args[2].isBlank() || "-".equals(args[2]) || (Files.exists(outputS) && !Files.isWritable(outputS))) {
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

                    writer().write("v1");
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
                writer().write("CLASS");
                writer().write("\t");
                writer().write(cls);
                writer().write("\t");
                writer().write(target);
                writer().newLine();
            }

            @Override
            public void acceptField(String cls, String name, String desc, String target) throws IOException {
                writer().write("FIELD");
                writer().write("\t");
                writer().write(cls);
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
                writer().write("METHOD");
                writer().write("\t");
                writer().write(cls);
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

            if (header.length < 3 || header.length > 4 || !header[0].trim().equals("v1")) {
                throw new RuntimeException("Invalid header!");
            }

            String srcNs = header[1];

            int clientNs = -1;
            int serverNs = -1;

            if (!"intermediary".equals(srcNs)) {
                throw new RuntimeException("invalid src namespace '" + srcNs + "'! only 'intermediary' is supported!");
            }
            for (int i = 2; i < header.length; i++) {
                String dstNs = header[i];

                if ("officialClient".equals(dstNs)) {
                    clientNs = i - 1;
                } else if ("officialServer".equals(dstNs)) {
                    serverNs = i - 1;
                } else {
                    throw new RuntimeException("invalid dst namespace '" + dstNs + "'! only 'officialClient' or 'officialServer' is supported!");
                }
            }

            String line;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                lineNumber++;
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }

                String[] parts = line.split("\t");
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].trim();
                    if (parts[i].isBlank()) {
                        parts[i] = null;
                    }
                }

                switch (parts[0]) {
                case "CLASS": {
                    String name = parts[1];
                    String client = clientNs > 0 ? parts[1 + clientNs] : null;
                    String server = serverNs > 0 && parts.length == 4 ? parts[1 + serverNs] : null;

                    cls.accept(name, client, server);

                    break;
                }
                case "FIELD": {
                    String clsName = parts[1];
                    String desc = parts[2];
                    String name = parts[3];
                    String client = clientNs > 0 ? parts[3 + clientNs] : null;
                    String server = serverNs > 0 && parts.length == 6 ? parts[3 + serverNs] : null;

                    fld.accept(new EntryTriple(clsName, name, desc), client, server);

                    break;
                }
                case "METHOD": {
                    String clsName = parts[1];
                    String desc = parts[2];
                    String name = parts[3];
                    String client = clientNs > 0 ? parts[3 + clientNs] : null;
                    String server = serverNs > 0 && parts.length == 6 ? parts[3 + serverNs] : null;

                    mtd.accept(new EntryTriple(clsName, name, desc), client, server);

                    break;
                }
                default:
                    throw new IllegalStateException("unsupported entry type " + parts[0]);
                }
            }
        } catch (Throwable t) {
            throw new IOException("error reading tiny file on line " + lineNumber, t);
        }
    }
}
