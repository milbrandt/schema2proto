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

import java.io.IOException;
import java.util.*;

import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.google.common.collect.ImmutableList;
import com.squareup.wire.schema.EnumConstant;
import com.squareup.wire.schema.EnumType;
import com.squareup.wire.schema.Extensions;
import com.squareup.wire.schema.Field;
import com.squareup.wire.schema.Field.Label;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.MessageType;
import com.squareup.wire.schema.OneOf;
import com.squareup.wire.schema.Options;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.ProtoFile.Syntax;
import com.squareup.wire.schema.ProtoType;
import com.squareup.wire.schema.Reserved;
import com.squareup.wire.schema.Type;
import com.squareup.wire.schema.internal.parser.OptionElement;
import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.util.DomAnnotationParserFactory;

public class SchemaParser implements ErrorHandler {

	public static final String TYPE_SUFFIX = "Type";
	public static final String GENERATED_NAME_SUFFIX_UNIQUENESS = "GeneratedBySchemaToProto";

	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaParser.class);

	private static final String DEFAULT_PROTO_PACKAGE = "default";
	public static final String VALIDATE_RULES_NAME = "validate.rules";

	private Map<String, ProtoFile> packageToProtoFileMap = new HashMap<>();

	private Map<String, String> simpleTypes;
	private Map<String, String> documentation;
	private Map<MessageType, Set<Object>> elementDeclarationsPerMessageType = new HashMap<>();
	private Set<String> basicTypes;
	private Map<String, OptionElement> defaultValidationRulesForBasicTypes;

	private Set<String> generatedTypeNames = new HashSet<>();

	private int nestlevel = 0;

	private Schema2ProtoConfiguration configuration;

	private void init() {
		simpleTypes = new HashMap<String, String>();
		documentation = new HashMap<String, String>();

		basicTypes = new TreeSet<String>();
		basicTypes.addAll(TypeRegistry.getBasicTypes());

		defaultValidationRulesForBasicTypes = new HashMap<>();
		defaultValidationRulesForBasicTypes.putAll(getValidationRuleForBasicTypes());

	}

	public SchemaParser(Schema2ProtoConfiguration configuration) {
		this.configuration = configuration;
		init();
	}

	public Map<String, ProtoFile> parse() throws SAXException, IOException {

		SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
		saxParserFactory.setNamespaceAware(true);

		XSOMParser parser = new XSOMParser(saxParserFactory);
		parser.setErrorHandler(this);

		parser.setAnnotationParser(new DomAnnotationParserFactory());
		parser.parse(configuration.xsdFile);

		processSchemaSet(parser.getResult());

		return packageToProtoFileMap;
	}

	private void addType(String namespace, Type type) {
		ProtoFile file = getProtoFileForNamespace(namespace);
		file.types().add(type);

		// Type auto generated collision avoidance
		String typeName = null;

		if (type instanceof MessageType) {
			MessageType mt = (MessageType) type;
			typeName = mt.getName();
		} else if (type instanceof EnumType) {
			typeName = ((EnumType) type).name();
		}
	}

	private ProtoFile getProtoFileForNamespace(String namespace) {
		String packageName = NamespaceHelper.xmlNamespaceToProtoPackage(namespace, configuration.forceProtoPackage);
		if (StringUtils.trimToNull(packageName) == null) {
			packageName = DEFAULT_PROTO_PACKAGE;
		}

		if (configuration.defaultProtoPackage != null) {
			packageName = configuration.defaultProtoPackage;
		}

		ProtoFile file = packageToProtoFileMap.get(packageName);
		if (file == null) {
			file = new ProtoFile(Syntax.PROTO_3, packageName);
			packageToProtoFileMap.put(packageName, file);
		}
		return file;
	}

	private Type getType(String namespace, String typeName) {
		ProtoFile protoFileForNamespace = getProtoFileForNamespace(namespace);
		for (Type t : protoFileForNamespace.types()) {
			if (t instanceof MessageType) {
				if (((MessageType) t).getName().equals(typeName)) {
					return t;
				}
			} else if (t instanceof EnumType) {
				if (((EnumType) t).name().equals(typeName)) {
					return t;
				}
			}
		}

		return null;
	}

	private void processSchemaSet(XSSchemaSet schemaSet) throws ConversionException {

		Iterator<XSSchema> schemas = schemaSet.iterateSchema();
		while (schemas.hasNext()) {
			XSSchema schema = schemas.next();
			if (!schema.getTargetNamespace().endsWith("/XMLSchema")) {
				final Iterator<XSSimpleType> simpleTypes = schema.iterateSimpleTypes();
				while (simpleTypes.hasNext()) {
					processSimpleType(simpleTypes.next(), null);
				}
				final Iterator<XSComplexType> complexTypes = schema.iterateComplexTypes();
				while (complexTypes.hasNext()) {
					processComplexType(complexTypes.next(), null, schemaSet, null, null);
				}
				final Iterator<XSElementDecl> elementDeclarations = schema.iterateElementDecls();
				while (elementDeclarations.hasNext()) {
					processElement(elementDeclarations.next(), schemaSet);
				}
			}
		}
	}

	private void processElement(XSElementDecl el, XSSchemaSet schemaSet) throws ConversionException {
		XSComplexType cType;
		XSSimpleType xs;

		if (el.getType() instanceof XSComplexType && el.getType() != schemaSet.getAnyType()) {
			cType = (XSComplexType) el.getType();
			processComplexType(cType, el.getName(), schemaSet, null, null);
		} else if (el.getType() instanceof XSSimpleType && el.getType() != schemaSet.getAnySimpleType()) {
			xs = el.getType().asSimpleType();
			processSimpleType(xs, el.getName());
		} else {
			LOGGER.info("Unhandled element " + el + " at " + el.getLocator().getSystemId() + " at line/col " + el.getLocator().getLineNumber() + "/"
					+ el.getLocator().getColumnNumber());
		}
	}

	/**
	 * @param xs
	 * @param elementName
	 */
	private String processSimpleType(XSSimpleType xs, String elementName) {

		nestlevel++;

		LOGGER.debug(StringUtils.leftPad(" ", nestlevel) + "SimpleType " + xs);

		String typeName = xs.getName();

		if (typeName == null) {
			typeName = elementName + GENERATED_NAME_SUFFIX_UNIQUENESS;
		}

		if (xs.isRestriction() && xs.getFacet("enumeration") != null) {
			createEnum(typeName, xs.asRestriction(), null);
		} else {
			// This is just a restriction on a basic type, find parent and messages
			// it to the type
			String baseTypeName = typeName;
			while (xs != null && !basicTypes.contains(baseTypeName)) {
				xs = xs.getBaseType().asSimpleType();
				if (xs != null) {
					baseTypeName = xs.getName();
				}
			}
			simpleTypes.put(typeName, xs != null ? xs.getName() : "string");
			String doc = resolveDocumentationAnnotation(xs);
			addDocumentation(typeName, doc);
		}

		nestlevel--;
		return typeName;
	}

	private void addDocumentation(String typeName, String doc) {
		if (doc != null) {
			documentation.put(typeName, doc);
		}
	}

	private void addField(MessageType message, Field field, boolean acceptDuplicate) {

		ImmutableList<Field> fields = message.fields();
		Field existingField = null;
		for (Field f : fields) {
			if (field.name().equals(f.name())) {
				existingField = f;
				break;
			}
		}

		if (existingField != null) {
			message.removeDeclaredField(existingField);
			if (acceptDuplicate) {
				field.setLabel(Label.REPEATED);
			}
		}

		message.addField(field);

	}

	private void navigateSubTypes(XSParticle parentParticle, MessageType messageType, Set<Object> processedXmlObjects, XSSchemaSet schemaSet,
			boolean isExtension) throws ConversionException {

		XSTerm currTerm = parentParticle.getTerm();
		if (currTerm.isElementDecl()) {
			XSElementDecl currElementDecl = currTerm.asElementDecl();

			if (!processedXmlObjects.contains(currElementDecl)) {
				processedXmlObjects.add(currElementDecl);

				XSType type = currElementDecl.getType();

				if (type != null && type.isComplexType() && type.getName() != null) {

					// COMPLEX TYPE

					String doc = resolveDocumentationAnnotation(currElementDecl);

					Options options = getFieldOptions(parentParticle);
					int tag = messageType.getNextFieldNum();
					Label label = getRange(parentParticle) ? Label.REPEATED : null;
					Location location = getLocation(currElementDecl);

					Field field = new Field(NamespaceHelper.xmlNamespaceToProtoFieldPackagename(type.getTargetNamespace(), configuration.forceProtoPackage),
							location, label, currElementDecl.getName(), doc, tag, null, type.getName(), options, false, true);
					addField(messageType, field, isExtension);

				} else {

					if (type.isSimpleType()) {

						String doc = resolveDocumentationAnnotation(currElementDecl);

						if (type.asSimpleType().isRestriction() && type.asSimpleType().getFacet("enumeration") != null) {

							String enumName = createEnum(currElementDecl.getName(), type.asSimpleType().asRestriction(), messageType);

							boolean extension = false;
							Options options = getFieldOptions(parentParticle);
							int tag = messageType.getNextFieldNum();
							Label label = getRange(parentParticle) ? Label.REPEATED : null;
							Location location = getLocation(currElementDecl);

							Field field = new Field(
									NamespaceHelper.xmlNamespaceToProtoFieldPackagename(type.getTargetNamespace(), configuration.forceProtoPackage), location,
									label, currElementDecl.getName(), doc, tag, null, enumName, options, extension, true);
							addField(messageType, field, isExtension);

							XSRestrictionSimpleType restriction = type.asSimpleType().asRestriction();
							// checkType(restriction.getBaseType());
							// TODO ENUM

						} else {
							//
							Options options = getFieldOptions(parentParticle);
							int tag = messageType.getNextFieldNum();
							Label label = getRange(parentParticle) ? Label.REPEATED : null;
							Location location = getLocation(currElementDecl);

							String typeName = findFieldType(type);

							String packageName = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(type.getTargetNamespace(),
									configuration.forceProtoPackage);

							Field field = new Field(basicTypes.contains(typeName) ? null : packageName, location, label, currElementDecl.getName(), doc, tag,
									null, typeName, options, false, true); // TODO add
							// restriction
							// as
							// validation
							// parameters
							addField(messageType, field, isExtension);
						}
					} else if (type.isComplexType()) {
						XSComplexType complexType = type.asComplexType();
						XSParticle particle;
						XSContentType contentType;
						contentType = complexType.getContentType();
						if ((particle = contentType.asParticle()) == null) {
							// jolieType.putSubType( createAnyOrUndefined( currElementDecl.getName(), complexType ) );
						}
						if (contentType.asSimpleType() != null) {
							// checkStrictModeForSimpleType(contentType);
						} else if ((particle = contentType.asParticle()) != null) {
							XSTerm term = particle.getTerm();
							XSModelGroupDecl modelGroupDecl = null;
							XSModelGroup modelGroup = null;
							modelGroup = getModelGroup(modelGroupDecl, term);
							if (modelGroup != null) {
								MessageType referencedMessageType = processComplexType(complexType, currElementDecl.getName(), schemaSet, null, null);

								String fieldDoc = resolveDocumentationAnnotation(currElementDecl);

								Options options = getFieldOptions(parentParticle);
								int tag = messageType.getNextFieldNum();
								Label label = getRange(parentParticle) ? Label.REPEATED : null;
								Location fieldLocation = getLocation(currElementDecl);

								Field field = new Field(
										NamespaceHelper.xmlNamespaceToProtoFieldPackagename(complexType.getTargetNamespace(), configuration.forceProtoPackage),
										fieldLocation, label, currElementDecl.getName(), fieldDoc, tag, null, referencedMessageType.getName(), options, false,
										true);
								addField(messageType, field, isExtension);

							}
						}
					}
				}
			}
		} else {
			XSModelGroupDecl modelGroupDecl = null;
			XSModelGroup modelGroup = null;
			modelGroup = getModelGroup(modelGroupDecl, currTerm);

			if (modelGroup != null) {

				groupProcessing(modelGroup, parentParticle, messageType, processedXmlObjects, schemaSet, isExtension);
			}

		}
	}

	@NotNull
	private Options getFieldOptions(XSParticle parentParticle) {
		List<OptionElement> optionElements = new ArrayList<OptionElement>();
		OptionElement validationRule = getValidationRule(parentParticle);
		if (validationRule != null) {
			optionElements.add(validationRule);
		}
		return new Options(Options.FIELD_OPTIONS, optionElements);
	}

	private OptionElement getValidationRule(XSParticle parentParticle) {

		if (configuration.includeValidationRules) {

			int minOccurs = 0; // Default
			int maxOccurs = 1; // Default

			if (parentParticle.getMinOccurs() != null) {
				minOccurs = parentParticle.getMinOccurs().intValue();
			}

			if (parentParticle.getMaxOccurs() != null) {
				maxOccurs = parentParticle.getMaxOccurs().intValue();
			}

			if (minOccurs == 1 && maxOccurs == 1) {

				/*
				 * OptionElement option = new OptionElement("message.required", OptionElement.Kind.BOOLEAN, true, false); OptionElement e = new
				 * OptionElement(VALIDATE_RULES_NAME, OptionElement.Kind.OPTION, option, true); return e;
				 */
			}
		}
		return null;

	}

	@NotNull
	private Options getFieldOptions(XSAttributeDecl attributeDecl) {
		List<OptionElement> optionElements = new ArrayList<OptionElement>();

		// First see if there are rules associated with attribute declaration
		OptionElement validationRule = getValidationRule(attributeDecl);
		if (validationRule != null) {
			optionElements.add(validationRule);
		} else {
			// Check attribute TYPE rules
			OptionElement typeRule = getValidationRule(attributeDecl.getType());
			if (typeRule != null) {
				optionElements.add(typeRule);
			}
		}
		return new Options(Options.FIELD_OPTIONS, optionElements);
	}

	private OptionElement getValidationRule(XSAttributeDecl attributeDecl) {
		if (configuration.includeValidationRules) {
		}
		// TOOD check if optional
		return null;
	}

	@NotNull
	private Options getFieldOptions(XSSimpleType attributeDecl) {
		List<OptionElement> optionElements = new ArrayList<OptionElement>();
		OptionElement validationRule = getValidationRule(attributeDecl);
		if (validationRule != null) {
			optionElements.add(validationRule);
		}
		return new Options(Options.FIELD_OPTIONS, optionElements);
	}

	private OptionElement getValidationRule(XSSimpleType simpleType) {
		if (configuration.includeValidationRules) {

			String typeName = simpleType.getName();

			if (typeName != null && basicTypes.contains(typeName)) {
				return getValidationRuleForBasicType(typeName);
			} else if (simpleType.isRestriction()) {
				XSRestrictionSimpleType restriction = simpleType.asRestriction();
				// XSType baseType = restriction.getBaseType();
				Collection<? extends XSFacet> declaredFacets = restriction.getDeclaredFacets();
				String baseType = findFieldType(simpleType);
				if ("string".equals(baseType)) {
					Map<String, Object> parameters = new HashMap<>();
					for (XSFacet facet : declaredFacets) {
						switch (facet.getName()) {
						case "pattern":
							parameters.put("pattern", facet.getValue().value);
							break;
						case "minLength":
							parameters.put("min_len", Integer.parseInt(facet.getValue().value));
							break;
						case "maxLength":
							parameters.put("max_len", Integer.parseInt(facet.getValue().value));
							break;

						}
					}
					OptionElement option = new OptionElement("string", OptionElement.Kind.MAP, parameters, false);
					OptionElement e = new OptionElement(VALIDATE_RULES_NAME, OptionElement.Kind.OPTION, option, true);
					return e;
				}

				// TODO check baseType, add restrictions on it.
				// TODO check if facets are inherited or not. If inherited then iterate to top primitive to find
				// base rule, then select supported facets
				// System.out.println("x");
			} else {
				LOGGER.warn("During validation rules extraction; Found anonymous simpleType that is not a restriction", simpleType);

			}

		}
		/*
		 * if (minOccurs == 1 && maxOccurs == 1) {
		 *
		 * OptionElement option = new OptionElement("message.required", OptionElement.Kind.BOOLEAN, true, false); OptionElement e = new
		 * OptionElement(VALIDATE_RULES_NAME, OptionElement.Kind.OPTION, option, true);
		 *
		 * return e; }
		 */

		return null;

	}

	private OptionElement getValidationRuleForBasicType(String name) {
		return defaultValidationRulesForBasicTypes.get(name);
	}

	private String findFieldType(XSType type) {
		String typeName = type.getName();
		if (typeName == null) {

			if (type.asSimpleType().isPrimitive()) {
				typeName = type.asSimpleType().getName();
			} else if (type.asSimpleType().isList()) {
				XSListSimpleType asList = type.asSimpleType().asList();
				XSSimpleType itemType = asList.getItemType();
				typeName = itemType.getName();
			} else {
				typeName = type.asSimpleType().getBaseType().getName();
			}

		} else {
			if (!basicTypes.contains(typeName)) {
				typeName = type.asSimpleType().getBaseType().getName();
			}
		}

		if ((typeName != null && !basicTypes.contains(typeName) || typeName == null) && type.isSimpleType() && type.asSimpleType().isRestriction()) {
			XSType restrictionBase = type.asSimpleType().asRestriction().getBaseType();
			return findFieldType(restrictionBase);
		}
		return typeName;
	}

	private boolean getRange(XSParticle part) {
		int max = 1;

		if (part.getMaxOccurs() != null) {
			max = part.getMaxOccurs().intValue();
		}

		return max > 1 || max == -1;
	}

	/**
	 * @param complexType
	 * @param elementName
	 * @param schemaSet
	 * @throws ConversionException
	 */
	private MessageType processComplexType(XSComplexType complexType, String elementName, XSSchemaSet schemaSet, MessageType messageType,
			Set<Object> processedXmlObjects) throws ConversionException {

		nestlevel++;

		LOGGER.debug(StringUtils.leftPad(" ", nestlevel) + "ComplexType " + complexType + ", proto " + messageType);

		boolean isBaseLevel = messageType == null;

		String typeName = null;
		if (messageType != null) {
			typeName = messageType.getName();
		}

		if (messageType == null) {

			typeName = complexType.getName();
			String nameSpace = complexType.getTargetNamespace();

			if (complexType.getScope() != null) {
				elementName = complexType.getScope().getName();
			}

			if (typeName == null) {
				typeName = elementName + GENERATED_NAME_SUFFIX_UNIQUENESS;
			}
			messageType = (MessageType) getType(nameSpace, typeName);

			if (messageType == null && !basicTypes.contains(typeName)) {

				String doc = resolveDocumentationAnnotation(complexType);

				List<OptionElement> messageOptions = new ArrayList<>();
				Options options = new Options(Options.MESSAGE_OPTIONS, messageOptions);
				Location location = getLocation(complexType);
				List<Field> fields = new ArrayList<>();
				List<Field> extensions = new ArrayList<>();
				List<OneOf> oneofs = new ArrayList<>();
				List<Type> nestedTypes = new ArrayList<>();
				List<Extensions> extendsions = new ArrayList<>();
				List<Reserved> reserved = new ArrayList<>();
				// Add message type to file
				messageType = new MessageType(ProtoType.get(typeName), location, doc, typeName, fields, extensions, oneofs, nestedTypes, extendsions, reserved,
						options);

				addType(nameSpace, messageType);

				processedXmlObjects = new HashSet<>();

				elementDeclarationsPerMessageType.put(messageType, processedXmlObjects);

			} else {
				LOGGER.debug(StringUtils.leftPad(" ", nestlevel) + "Already processed ComplexType " + typeName + ", ignored");
				nestlevel--;
				return messageType;
			}
		}

		XSType parent = complexType.getBaseType();

		if (configuration.inheritanceToComposition) {

			List<MessageType> parentTypes = new ArrayList<>();

			while (parent != schemaSet.getAnyType() && parent.isComplexType()) {

				// Ensure no duplicate element parsing
				MessageType parentType = processComplexType(parent.asComplexType(), elementName, schemaSet, null, null);
				processedXmlObjects.addAll(elementDeclarationsPerMessageType.get(parentType));

				parentTypes.add(parentType);
				parent = parent.getBaseType();
			}

			if (!complexType.isAbstract()) {
				Collections.reverse(parentTypes);
				for (MessageType parentMessageType : parentTypes) {
					String fieldDoc = parentMessageType.documentation();
					boolean extension = false;
					List<OptionElement> optionElements = new ArrayList<OptionElement>();
					Options fieldOptions = new Options(Options.FIELD_OPTIONS, optionElements);
					int tag = messageType.getNextFieldNum();
					Label label = null;
					Location fieldLocation = getLocation(complexType);

					Field field = new Field(findPackageNameForType(parentMessageType), fieldLocation, label, parentMessageType.getName(), fieldDoc, tag, null,
							parentMessageType.getName(), fieldOptions, extension, true);

					addField(messageType, field, false);
				}
				if (parentTypes.size() > 0) {
					messageType.advanceFieldNum();
				}
			}

		} else {
			if (parent != schemaSet.getAnyType() && parent.isComplexType()) {
				processComplexType(parent.asComplexType(), elementName, schemaSet, messageType, processedXmlObjects);
			}
		}

		if (complexType.getAttributeUses() != null) {
			processAttributes(complexType, messageType, processedXmlObjects);
		}

		boolean isExtension = complexType.getExplicitContent() == null ? false : true;

		if (complexType.getContentType().asParticle() != null) {
			XSParticle particle = complexType.getContentType().asParticle();

			XSTerm term = particle.getTerm();
			XSModelGroupDecl modelGroupDecl = null;
			XSModelGroup modelGroup = null;
			modelGroup = getModelGroup(modelGroupDecl, term);

			if (modelGroup != null) {
				groupProcessing(modelGroup, particle, messageType, processedXmlObjects, schemaSet, isExtension);
			}

		} else if (complexType.getContentType().asSimpleType() != null) {
			XSSimpleType xsSimpleType = complexType.getContentType().asSimpleType();

			if (isBaseLevel) { // Only add simpleContent from concrete type?
				/*
				 * if(!processedXmlObjects.contains(xsSimpleType)) processedXmlObjects.add(xsSimpleType);
				 */
				String name = xsSimpleType.getName();
				if (name == null) {
					// Add simple field
					boolean extension = false;
					Options options = getFieldOptions(xsSimpleType);
					int tag = messageType.getNextFieldNum();

					Location fieldLocation = getLocation(xsSimpleType);

					String simpleTypeName = findFieldType(xsSimpleType);

					String packageName = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(xsSimpleType.getTargetNamespace(),
							configuration.forceProtoPackage);

					Field field = new Field(basicTypes.contains(simpleTypeName) ? null : packageName, fieldLocation, null, "value",
							"SimpleContent value of element", tag, null, simpleTypeName, options, extension, true);
					addField(messageType, field, false);

				} else if (basicTypes.contains(xsSimpleType.getName())) {

					// Add simple field
					boolean extension = false;
					Options options = getFieldOptions(xsSimpleType);
					int tag = messageType.getNextFieldNum();
					// Label label = attr. ? Label.REPEATED : null;
					Location fieldLocation = getLocation(xsSimpleType);

					Field field = new Field(null, fieldLocation, null, "value", "SimpleContent value of element", tag, null, xsSimpleType.getName(), options,
							extension, true);
					addField(messageType, field, false);

				} else {
					XSSimpleType primitiveType = xsSimpleType.getPrimitiveType();
					if (primitiveType != null) {

						// Add simple field
						boolean extension = false;
						List<OptionElement> optionElements = new ArrayList<>();
						Options fieldOptions = new Options(Options.FIELD_OPTIONS, optionElements);
						int tag = messageType.getNextFieldNum();
						// Label label = attr. ? Label.REPEATED : null;

						Location fieldLocation = getLocation(xsSimpleType);

						Field field = new Field(null, fieldLocation, null, "value", "SimpleContent value of element", tag, null, primitiveType.getName(),
								fieldOptions, extension, true);
						addField(messageType, field, false);

					}
				}
			}
		}

		nestlevel--;
		return messageType;

	}

	private String findPackageNameForType(MessageType parentMessageType) {
		// Slow and dodgy
		for (Map.Entry<String, ProtoFile> packageAndProtoFile : packageToProtoFileMap.entrySet()) {
			ProtoFile file = packageAndProtoFile.getValue();
			for (Type t : file.types()) {
				if (t == parentMessageType) {
					return packageAndProtoFile.getKey();
				}
			}

		}
		return null;
	}

	private Location getLocation(XSComponent t) {
		Locator l = t.getLocator();
		return new Location("", l.getSystemId(), l.getLineNumber(), l.getColumnNumber());
	}

	private void processAttributes(XSAttContainer complexType, MessageType messageType, Set<Object> processedXmlObjects) {
		Iterator<? extends XSAttributeUse> iterator = complexType.iterateDeclaredAttributeUses();
		while (iterator.hasNext()) {
			XSAttributeUse attr = iterator.next();
			processAttribute(messageType, processedXmlObjects, attr);
		}

		Iterator<? extends XSAttGroupDecl> iterateAttGroups = complexType.iterateAttGroups();
		while (iterateAttGroups.hasNext()) {
			// Recursive
			processAttributes(iterateAttGroups.next(), messageType, processedXmlObjects);
		}

	}

	private void processAttribute(MessageType messageType, Set<Object> processedXmlObjects, XSAttributeUse attr) {
		if (!processedXmlObjects.contains(attr)) {
			processedXmlObjects.add(attr);

			XSAttributeDecl decl = attr.getDecl();

			if (decl.getType().getPrimitiveType() != null) {
				String fieldName = decl.getName();
				String doc = resolveDocumentationAnnotation(decl);

				if (decl.getType().isRestriction() && decl.getType().getFacet("enumeration") != null) {

					String enumName = createEnum(fieldName, decl.getType().asRestriction(), decl.isLocal() ? messageType : null);

					boolean extension = false;
					List<OptionElement> optionElements = new ArrayList<OptionElement>();
					Options fieldOptions = new Options(Options.FIELD_OPTIONS, optionElements);
					int tag = messageType.getNextFieldNum();
					// Label label = attr. ? Label.REPEATED : null;
					Location fieldLocation = getLocation(decl);

					Field field = new Field(
							NamespaceHelper.xmlNamespaceToProtoFieldPackagename(decl.getType().getTargetNamespace(), configuration.forceProtoPackage),
							fieldLocation, null, fieldName, doc, tag, null, enumName, fieldOptions, extension, false);
					addField(messageType, field, false);

				} else {

					Options options = getFieldOptions(decl);
					int tag = messageType.getNextFieldNum();
					// Label label = attr. ? Label.REPEATED : null;
					Location fieldLocation = getLocation(decl);

					String typeName = findFieldType(decl.getType());

					String packageName = NamespaceHelper.xmlNamespaceToProtoFieldPackagename(decl.getType().getTargetNamespace(),
							configuration.forceProtoPackage);

					Field field = new Field(basicTypes.contains(typeName) ? null : packageName, fieldLocation, null, fieldName, doc, tag, null, typeName,
							options, false, false);
					addField(messageType, field, false);

				}
			}
		}
	}

	private XSModelGroup getModelGroup(XSModelGroupDecl modelGroupDecl, XSTerm term) {
		if ((modelGroupDecl = term.asModelGroupDecl()) != null) {
			return modelGroupDecl.getModelGroup();
		} else if (term.isModelGroup()) {
			return term.asModelGroup();
		} else {
			return null;
		}
	}

	private void groupProcessing(XSModelGroup modelGroup, XSParticle particle, MessageType messageType, Set<Object> processedXmlObjects, XSSchemaSet schemaSet,
			boolean isExtension) throws ConversionException {
		XSModelGroup.Compositor compositor = modelGroup.getCompositor();

		// We handle "all" and "sequence", but not "choice"
		// if (compositor.equals(XSModelGroup.ALL) || compositor.equals(XSModelGroup.SEQUENCE)) {

		XSParticle[] children = modelGroup.getChildren();

		for (int i = 0; i < children.length; i++) {
			XSTerm currTerm = children[i].getTerm();
			if (currTerm.isModelGroup()) {
				groupProcessing(currTerm.asModelGroup(), particle, messageType, processedXmlObjects, schemaSet, isExtension);
			} else {
				// Create the new complex type for root types
				navigateSubTypes(children[i], messageType, processedXmlObjects, schemaSet, isExtension);
			}
		}
//		} else if (compositor.equals(XSModelGroup.CHOICE)) {
//			throw new ConversionException("no choice support");
//		}
		messageType.advanceFieldNum();

	}

	private String resolveDocumentationAnnotation(XSComponent xsComponent) {
		String doc = "";
		if (xsComponent.getAnnotation() != null && xsComponent.getAnnotation().getAnnotation() != null) {
			if (xsComponent.getAnnotation().getAnnotation() instanceof Node) {
				Node annotationEl = (Node) xsComponent.getAnnotation().getAnnotation();
				NodeList annotations = annotationEl.getChildNodes();

				for (int i = 0; i < annotations.getLength(); i++) {
					Node annotation = annotations.item(i);
					if ("documentation".equals(annotation.getLocalName())) {

						NodeList childNodes = annotation.getChildNodes();
						for (int j = 0; j < childNodes.getLength(); j++) {
							if (childNodes.item(j) != null && childNodes.item(j) instanceof Text) {
								doc = childNodes.item(j).getNodeValue();
							}
						}
					}
				}
			}
		}

		String[] lines = doc.split("\n");
		StringBuilder b = new StringBuilder();
		for (String line : lines) {
			b.append(StringUtils.trimToEmpty(line));
			b.append(" ");
		}

		if (configuration.includeSourceLocationInDoc) {
			if (xsComponent != null && xsComponent.getLocator() != null) {
				Location loc = getLocation(xsComponent);
				b.append(" [");
				b.append(loc.toString());
				b.append("]");
			}
		}
		return StringUtils.trimToEmpty(b.toString());
	}

	private String createEnum(String elementName, XSRestrictionSimpleType type, MessageType enclosingType) {
		Iterator<? extends XSFacet> it;

		String typeNameToUse = null;

		if (type.getName() != null) {
			typeNameToUse = type.getName();
			enclosingType = null;
		} else {
			if (enclosingType != null) {
				typeNameToUse = elementName + TYPE_SUFFIX;
			} else {
				typeNameToUse = elementName + GENERATED_NAME_SUFFIX_UNIQUENESS;
			}
		}

		Type protoType = getType(type.getTargetNamespace(), typeNameToUse);
		if (protoType == null) {

			type = type.asRestriction();

			Location location = getLocation(type);

			List<EnumConstant> constants = new ArrayList<EnumConstant>();
			it = type.getDeclaredFacets().iterator();

			int counter = 1;
			Set<String> addedValues = new HashSet<>();
			while (it.hasNext()) {
				List<OptionElement> optionElements = new ArrayList<>();
				XSFacet next = it.next();
				String doc = resolveDocumentationAnnotation(next);
				String enumValue = next.getValue().value;

				if (!addedValues.contains(enumValue)) {
					addedValues.add(enumValue);
					constants.add(new EnumConstant(location, enumValue, counter++, doc, new Options(Options.ENUM_VALUE_OPTIONS, optionElements)));
				}
			}

			List<OptionElement> enumOptionElements = new ArrayList<>();
			Options enumOptions = new Options(Options.ENUM_OPTIONS, enumOptionElements);

			String doc = resolveDocumentationAnnotation(type);

			ProtoType definedProtoType;
			if (enclosingType == null) {
				definedProtoType = ProtoType.get(typeNameToUse);
			} else {
				definedProtoType = ProtoType.get(enclosingType.getName(), typeNameToUse);
			}

			EnumType enumType = new EnumType(definedProtoType, location, doc, typeNameToUse, constants, enumOptions);

			if (enclosingType != null) {
				// if not already present
				boolean alreadyPresentAsNestedType = false;
				for (Type t : enclosingType.nestedTypes()) {
					if (t instanceof EnumType && ((EnumType) t).name().equals(typeNameToUse)) {
						alreadyPresentAsNestedType = true;
						break;
					}
				}
				if (!alreadyPresentAsNestedType) {
					enclosingType.nestedTypes().add(enumType);
				}
			} else {
				addType(type.getTargetNamespace(), enumType);
			}
		}
		return typeNameToUse;
	}

	@Override
	public void error(SAXParseException exception) throws SAXException {
		LOGGER.error(exception.getMessage() + " at " + exception.getSystemId());
		exception.printStackTrace();
	}

	@Override
	public void fatalError(SAXParseException exception) throws SAXException {
		LOGGER.error(exception.getMessage() + " at " + exception.getSystemId());
		exception.printStackTrace();
	}

	@Override
	public void warning(SAXParseException exception) throws SAXException {
		LOGGER.warn(exception.getMessage() + " at " + exception.getSystemId());
		exception.printStackTrace();
	}

	public Map<String, OptionElement> getValidationRuleForBasicTypes() {

		Map<String, OptionElement> basicTypes = new HashMap<>();

//        basicTypes.add("string");
//        basicTypes.add("boolean");
//        basicTypes.add("float");
//        basicTypes.add("double");
//        basicTypes.add("decimal");
//        basicTypes.add("duration");
//        basicTypes.add("dateTime");
//        basicTypes.add("time");
//        basicTypes.add("date");

		basicTypes.put("gYearMonth", createOptionElement("string.pattern", OptionElement.Kind.STRING, "[0-9]{4}-[0-9]{2}"));
		basicTypes.put("gYear", createOptionElement("string.pattern", OptionElement.Kind.STRING, "[0-9]{4}"));
		basicTypes.put("gMonthDay", createOptionElement("string.pattern", OptionElement.Kind.STRING, "[0-9]{4}-[0-9]{2}"));
		basicTypes.put("gDay", createOptionElement("string.pattern", OptionElement.Kind.STRING, "[0-9]{2}")); // 1-31
		basicTypes.put("gMonth", createOptionElement("string.pattern", OptionElement.Kind.STRING, "[0-9]{2}")); // 1-12

//        basicTypes.add("hexBinary");
//        basicTypes.add("base64Binary");
//        basicTypes.add("anyURI");
//        basicTypes.add("QName");
//        basicTypes.add("NOTATION");
//
//        basicTypes.add("normalizedString");
//        basicTypes.add("token");

		basicTypes.put("language", createOptionElement("string.pattern", OptionElement.Kind.STRING, "[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*"));

//        basicTypes.put("IDREFS");
//        basicTypes.put("ENTITIES");
//        basicTypes.put("NMTOKEN");
//        basicTypes.put("NMTOKENS");
//        basicTypes.put("Name");
//        basicTypes.put("NCName");
//        basicTypes.put("ID");
//        basicTypes.put("IDREF");
//        basicTypes.put("ENTITY");

//        basicTypes.put("integer");

		basicTypes.put("nonPositiveInteger", createOptionElement("sint32.lte", OptionElement.Kind.NUMBER, 0));
		basicTypes.put("negativeInteger", createOptionElement("sint32.lt", OptionElement.Kind.NUMBER, 0));
//        basicTypes.put("long");
//        basicTypes.put("int");
//        basicTypes.put("short");
//        basicTypes.put("byte");

		basicTypes.put("nonNegativeInteger", createOptionElement("uint32.gte", OptionElement.Kind.NUMBER, 0));
//        basicTypes.put("unsignedLong");
//        basicTypes.put("unsignedInt");
//        basicTypes.put("unsignedShort");
//        basicTypes.put("unsignedByte");
		basicTypes.put("positiveInteger", createOptionElement("uint32.gt", OptionElement.Kind.NUMBER, 0));

//        basicTypes.put("anySimpleType");
//        basicTypes.put("anyType");

		return basicTypes;

	}

	private OptionElement createOptionElement(String name, OptionElement.Kind kind, Object value) {
		OptionElement option = new OptionElement(name, kind, value, false);
		OptionElement wrapper = new OptionElement(VALIDATE_RULES_NAME, OptionElement.Kind.OPTION, option, true);

		return wrapper;
	}

}
