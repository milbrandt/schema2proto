package no.entur.schema2proto;

/*-
 * #%L
 * schema2proto-lib
 * %%
 * Copyright (C) 2019 Entur
 * %%
 * Licensed under the EUPL, Version 1.1 or – as soon they will be
 * approved by the European Commission - subsequent versions of the
 * EUPL (the "Licence");
 * 
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 * http://ec.europa.eu/idabc/eupl5
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * #L%
 */

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import com.squareup.wire.schema.IdentifierSet;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;

public class ReduceProto {

	private static final Logger LOGGER = LoggerFactory.getLogger(Schema2Proto.class);

	public void reduceProto(File configFile) throws IOException, InvalidConfigurationException {

		ReduceProtoConfiguration configuration = new ReduceProtoConfiguration();

		try (InputStream in = Files.newInputStream(configFile.toPath())) {
			LOGGER.info("Using configFile {}", configFile);
			Yaml yaml = new Yaml();
			ReduceProtoConfigFile config = yaml.loadAs(in, ReduceProtoConfigFile.class);

			if (config.outputDirectory == null) {
				throw new InvalidConfigurationException("No output directory");
			} else {
				configuration.outputDirectory = new File(config.outputDirectory);
				configuration.outputDirectory.mkdirs();
			}

			if (config.inputDirectory == null) {
				throw new InvalidConfigurationException("no input directory");
			} else {
				configuration.inputDirectory = new File(config.inputDirectory);
			}

			if (config.includes == null || config.includes.size() == 0 || config.excludes == null || config.excludes.size() == 0) {
				throw new InvalidConfigurationException("No includes/excludes - why are you running this tool?");
			} else {
				configuration.includes = config.includes;
				configuration.excludes = config.excludes;
			}

			if (config.customImportLocations != null) {
				configuration.customImportLocations = new ArrayList<>(config.customImportLocations);
			}

			reduceProto(configuration);

		}
	}

	public void reduceProto(ReduceProtoConfiguration configuration) throws IOException {
		SchemaLoader schemaLoader = new SchemaLoader();

		for (String importRootFolder : configuration.customImportLocations) {
			schemaLoader.addSource(new File(importRootFolder).toPath());
		}

		schemaLoader.addSource(configuration.inputDirectory.toPath());
		Schema schema = schemaLoader.load();

		IdentifierSet.Builder b = new IdentifierSet.Builder();
		b.exclude(configuration.excludes);
		b.include(configuration.includes);

		Schema prunedSchema = schema.prune(b.build());

		List<File> writtenProtoFiles = new ArrayList<>();

		for (ProtoFile protoFile : prunedSchema.protoFiles()) {

			if ("google.protobuf".equals(protoFile.packageName())) {
				// Ignore dependencies and descriptor
				continue;
			}

			File outputFile = new File(configuration.outputDirectory, protoFile.location().getPath());
			outputFile.getParentFile().mkdirs();
			Writer writer = new FileWriter(outputFile);
			writer.write(protoFile.toSchema());
			writer.close();

			writtenProtoFiles.add(outputFile);
		}
	}

}