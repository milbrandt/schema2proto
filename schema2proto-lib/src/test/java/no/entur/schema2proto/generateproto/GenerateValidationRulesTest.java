package no.entur.schema2proto.generateproto;

/*-
 * #%L
 * schema2proto-lib
 * %%
 * Copyright (C) 2019 - 2020 Entur
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

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import no.entur.schema2proto.AbstractMappingTest;

public class GenerateValidationRulesTest extends AbstractMappingTest {

	@Test
	public void messageIsRequired() throws IOException {
		generateProtobuf("test-message-required.xsd", null, null, "default", false, validationOptions());
		String generated = IOUtils.toString(Files.newInputStream(Paths.get("target/generated-proto/default/default.proto")), Charset.defaultCharset());
		Assertions.assertEquals(generated,
				"// default.proto at 0:0\n" + "syntax = \"proto3\";\n" + "package default;\n" + "\n" + "import \"validate/validate.proto\";\n" + "\n"
						+ "message TestRangeInt {\n" + "  int32 value = 1 [\n" + "    (validate.rules).message.required = true\n" + "  ];\n" + "}\n");
	}

	@Test
	public void repeatedFieldWithRange() throws IOException {
		generateProtobuf("test-min-max-occurs-range.xsd", null, null, "default", false, validationOptions());
		String generated = IOUtils.toString(Files.newInputStream(Paths.get("target/generated-proto/default/default.proto")), Charset.defaultCharset());
		Assertions.assertEquals(generated,
				"// default.proto at 0:0\n" + "syntax = \"proto3\";\n" + "package default;\n" + "\n" + "import \"validate/validate.proto\";\n" + "\n"
						+ "message TestRangeDecimal {\n" + "  repeated double value = 1 [\n" + "    (validate.rules).repeated = {\n" + "      min_items: 1,\n"
						+ "      max_items: 2147483647\n" + "    }\n" + "  ];\n" + "}\n");
	}

	private Map<String, Object> validationOptions() {
		Map<String, Object> options = new HashMap<>();
		options.put("includeValidationRules", true);
		options.put("customImportLocations", "src/test/resources");
		return options;
	}
}
