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
import java.util.function.BiConsumer;

import net.fabricmc.mapping.util.EntryTriple;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.combine.IntermediaryCombiner;

public class CommandCombineTiny extends Command {

    public CommandCombineTiny() {
        super("combineTiny");
    }

    @Override
    public String getHelpString() {
        return "<input-client> <input-server> <combined output>";
    }

    @Override
    public boolean isArgumentCountValid(int count) {
        return count == 3;
    }

    @Override
    public void run(String[] args) throws Exception {
        Path inputC = Paths.get(args[0]);
        Path inputS = Paths.get(args[1]);
        Path output = Paths.get(args[2]);

        if (Objects.equals(inputC, inputS) || Objects.equals(inputC, output) || Objects.equals(inputS, output)) {
            throw new IllegalArgumentException("input and output files cannot be the  same!");
        }

        IntermediaryCombiner combiner = new IntermediaryCombiner();

        if (!args[0].isBlank() && !"-".equals(args[0]) && Files.exists(inputC)) {
            System.out.println("Reading " + inputC);
            combiner.readClient(inputC, this::readInput);
        }
        if (!args[1].isBlank() && !"-".equals(args[1]) && Files.exists(inputS)) {
            System.out.println("Reading " + inputS);
            combiner.readServer(inputS, this::readInput);
        }

        System.out.println("Writing " + output);
        try (BufferedWriter bw = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            bw.write("v1");
            bw.write("\t");
            bw.write("intermediary");
            bw.write("\t");
            bw.write("officialClient");
            bw.write("\t");
            bw.write("officialServer");
            bw.newLine();

            combiner.writeCombined((cls, clsC, clsS) -> {
                bw.write("CLASS");
                bw.write("\t");
                bw.write(cls);
                bw.write("\t");
                bw.write(clsC);
                bw.write("\t");
                bw.write(clsS);
                bw.newLine();
            }, (cls, fld, fldDesc, fldC, fldS) -> {
                bw.write("FIELD");
                bw.write("\t");
                bw.write(cls);
                bw.write("\t");
                bw.write(fldDesc);
                bw.write("\t");
                bw.write(fld);
                bw.write("\t");
                bw.write(fldC);
                bw.write("\t");
                bw.write(fldS);
                bw.newLine();
            }, (cls, mtd, mtdDesc, mtdC, mtdS) -> {
                bw.write("METHOD");
                bw.write("\t");
                bw.write(cls);
                bw.write("\t");
                bw.write(mtdDesc);
                bw.write("\t");
                bw.write(mtd);
                bw.write("\t");
                bw.write(mtdC);
                bw.write("\t");
                bw.write(mtdS);
                bw.newLine();
            });
        }
        System.out.println("Done!");
    }

    private void readInput(Path input, BiConsumer<String, String> cls, BiConsumer<EntryTriple, String> fld, BiConsumer<EntryTriple, String> mtd) throws IOException {
        int lineNumber = 0;

        try (BufferedReader br = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            lineNumber++;
            String[] header = br.readLine().split("\t");

            if (header.length != 3 || !header[0].trim().equals("v1")) {
                throw new RuntimeException("Invalid header!");
            }

            String srcNs = header[1];
            String dstNs = header[2];

            if (!"official".equals(srcNs)) {
                throw new RuntimeException("invalid src namespace! only 'official' is supported!");
            }
            if (!"intermediary".equals(dstNs)) {
                throw new RuntimeException("invalid dst namespace! only 'intermediary' is supported!");
            }

            String line;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') {
                    continue;
                }

                String[] parts = line.split("\t");
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].trim();
                }

                switch (parts[0]) {
                case "CLASS": {
                    String name = parts[1];
                    String target = parts[2];

                    cls.accept(name, target);

                    break;
                }
                case "FIELD": {
                    String clsName = parts[1];
                    String desc = parts[2];
                    String name = parts[3];
                    String target = parts[4];

                    fld.accept(new EntryTriple(clsName, name, desc), target);

                    break;
                }
                case "METHOD": {
                    String clsName = parts[1];
                    String desc = parts[2];
                    String name = parts[3];
                    String target = parts[4];

                    mtd.accept(new EntryTriple(clsName, name, desc), target);

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
