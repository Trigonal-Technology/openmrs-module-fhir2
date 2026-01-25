/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.translators.impl;

import static lombok.AccessLevel.PROTECTED;

import javax.annotation.Nonnull;

import java.util.Locale;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ObservationDefinition;
import org.hl7.fhir.r4.model.ObservationDefinition.ObservationDataType;
import org.hl7.fhir.r4.model.ObservationDefinition.ObservationDefinitionQualifiedIntervalComponent;
import org.hl7.fhir.r4.model.ObservationDefinition.ObservationDefinitionQuantitativeDetailsComponent;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Range;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.ValueSet;
import org.openmrs.Concept;
import org.openmrs.ConceptAnswer;
import org.openmrs.ConceptClass;
import org.openmrs.ConceptDatatype;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptMapType;
import org.openmrs.ConceptName;
import org.openmrs.ConceptNumeric;
import org.openmrs.ConceptReferenceTerm;
import org.openmrs.ConceptSource;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirConceptService;
import org.openmrs.module.fhir2.api.FhirConceptSourceService;
import org.openmrs.module.fhir2.api.FhirValueSetService;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.translators.TestDefinitionTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Minimal, conservative implementation of {@link TestDefinitionTranslator}. For now, this only maps
 * basic properties required to create a simple textual test definition Concept from an
 * {@link ObservationDefinition}: - concept class: resolved via
 * ConceptService.getConceptClassByName("Test") - datatype: resolved via
 * ConceptService.getConceptDatatypeByName("Text") - name: taken from
 * ObservationDefinition.code.text (fallback to id if needed) Numeric, coded and other specialised
 * behaviours will be added incrementally in later phases to avoid breaking existing behaviour.
 */
@Slf4j
@Component
public class TestDefinitionTranslatorImpl implements TestDefinitionTranslator {
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private ConceptService conceptService;
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private FhirValueSetService fhirValueSetService;
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private FhirConceptSourceService fhirConceptSourceService;
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private FhirConceptService fhirConceptService;
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private ConceptTranslator conceptTranslator;
	
	@Override
	public ObservationDefinition toFhirResource(@Nonnull Concept concept) {
		if (concept == null) {
			return null;
		}
		
		ObservationDefinition defn = new ObservationDefinition();
		defn.setId(concept.getUuid());
		
		CodeableConcept code = null;
		if (conceptTranslator != null) {
			code = conceptTranslator.toFhirResource(concept);
		}
		if (code == null) {
			code = new CodeableConcept();
			code.setText(concept.getDisplayString());
		}
		defn.setCode(code);
		
		if (concept.getDatatype() != null && "Coded".equalsIgnoreCase(concept.getDatatype().getName())) {
			defn.addPermittedDataType(ObservationDataType.CODEABLECONCEPT);
		}
		
		if (concept instanceof ConceptNumeric) {
			populateNumericDefinition((ConceptNumeric) concept, defn);
			populateNumericIntervals((ConceptNumeric) concept, defn);
		}
		
		return defn;
	}
	
	@Override
	public Concept toOpenmrsType(@Nonnull ObservationDefinition definition) {
		Concept concept = findReusableTestConcept(definition);
		if (concept == null) {
			concept = createConceptSkeleton(definition);
		}
		return toOpenmrsType(concept, definition);
	}
	
	@Override
	public Concept toOpenmrsType(@Nonnull Concept existing, @Nonnull ObservationDefinition definition) {
		log.debug(
		    "TestDefinitionTranslatorImpl.toOpenmrsType entered: existingUuid={} hasId={} idPart={} permittedDataTypes={}",
		    existing != null ? existing.getUuid() : null, definition != null && definition.hasId(),
		    definition != null && definition.getIdElement() != null ? definition.getIdElement().getIdPart() : null,
		    definition != null && definition.hasPermittedDataType() ? definition.getPermittedDataType() : null);
		
		if (definition.hasId() && definition.getIdElement() != null) {
			String guid = definition.getIdElement().getIdPart();
			if (guid != null && !guid.trim().isEmpty()) {
				String trimmedGuid = guid.trim();
				// Case 1: brand new Concept with no UUID set at all
				if (existing.getUuid() == null) {
					existing.setUuid(trimmedGuid);
				}
				// Case 2: Concept has a generated UUID but is still unsaved (no DB id yet);
				// let the GUID from FHIR win before the Concept is persisted
				else if (existing.getId() == null && !trimmedGuid.equals(existing.getUuid())) {
					existing.setUuid(trimmedGuid);
				}
				// Case 3: existing.getId() != null -> persisted Concept; never change UUID in that case
			}
		}
		
		// Resolve concept class for tests
		ConceptClass testClass = conceptService.getConceptClassByName("Test");
		if (testClass == null) {
			throw new IllegalStateException("Concept class 'Test' not found; cannot create test definition concept");
		}
		existing.setConceptClass(testClass);
		
		ConceptDatatype datatype;
		if (existing instanceof ConceptNumeric || isNumericDefinition(definition)) {
			ConceptDatatype numericDatatype = conceptService.getConceptDatatypeByName("Numeric");
			if (numericDatatype == null) {
				throw new IllegalStateException(
				        "Concept datatype 'Numeric' not found; cannot create numeric test definition concept");
			}
			datatype = numericDatatype;
		} else if (isCodedDefinition(definition)) {
			ConceptDatatype codedDatatype = conceptService.getConceptDatatypeByName("Coded");
			if (codedDatatype == null) {
				throw new IllegalStateException(
				        "Concept datatype 'Coded' not found; cannot create coded test definition concept");
			}
			datatype = codedDatatype;
		} else {
			ConceptDatatype textDatatype = conceptService.getConceptDatatypeByName("Text");
			if (textDatatype == null) {
				throw new IllegalStateException("Concept datatype 'Text' not found; cannot create test definition concept");
			}
			datatype = textDatatype;
		}
		existing.setDatatype(datatype);
		log.debug(
		    "TestDefinitionTranslatorImpl.toOpenmrsType configured concept uuid={} class={} datatype={} isCodedDefinition={} hasValidCodedValueSet={}",
		    existing.getUuid(), existing.getConceptClass() != null ? existing.getConceptClass().getName() : null,
		    existing.getDatatype() != null ? existing.getDatatype().getName() : null, isCodedDefinition(definition),
		    definition != null && definition.hasValidCodedValueSet());
		
		// Create a basic fully specified name from ObservationDefinition.code.text
		String nameText = null;
		if (definition.getCode() != null && definition.getCode().hasText()) {
			nameText = definition.getCode().getText();
		}
		if (nameText == null && definition.getIdElement() != null) {
			nameText = definition.getIdElement().getIdPart();
		}
		
		if (nameText == null || nameText.trim().isEmpty()) {
			throw new IllegalStateException("ObservationDefinition must provide code.text or id to derive a concept name");
		}
		
		applyOrUpdatePrimaryName(existing, nameText.trim());
		applyCodeMappings(existing, definition);
		
		if (existing instanceof ConceptNumeric) {
			applyNumericDetails((ConceptNumeric) existing, definition);
		}
		
		if (isCodedDefinition(definition)) {
			log.debug(
			    "TestDefinitionTranslatorImpl.toOpenmrsType applying coded answers: conceptUuid={} defnId={} hasValidCodedValueSet={} validCodedValueSetRef={}",
			    existing.getUuid(), definition.getIdElement() != null ? definition.getIdElement().getIdPart() : null,
			    definition.hasValidCodedValueSet(),
			    definition.hasValidCodedValueSet() && definition.getValidCodedValueSet() != null
			            ? definition.getValidCodedValueSet().getReference()
			            : null);
			applyAnswersFromValidCodedValueSet(existing, definition);
		}
		
		return existing;
	}
	
	private void applyOrUpdatePrimaryName(Concept concept, String nameText) {
		if (concept == null || nameText == null) {
			return;
		}
		String trimmed = nameText.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		
		Locale locale = Context.getLocale();
		ConceptName primary = concept.getFullySpecifiedName(locale);
		if (primary == null) {
			primary = concept.getName(locale);
		}
		if (primary == null && concept.getNames() != null) {
			for (ConceptName candidate : concept.getNames()) {
				if (candidate != null && locale.equals(candidate.getLocale())) {
					primary = candidate;
					break;
				}
			}
		}
		
		if (primary != null) {
			if (!trimmed.equals(primary.getName())) {
				primary.setName(trimmed);
			}
			return;
		}
		
		ConceptName name = new ConceptName();
		name.setName(trimmed);
		name.setLocale(locale);
		concept.addName(name);
	}
	
	private void applyCodeMappings(Concept concept, ObservationDefinition definition) {
		if (concept == null || definition == null) {
			return;
		}
		
		CodeableConcept code = definition.getCode();
		if (code == null || !code.hasCoding()) {
			return;
		}
		
		for (Coding coding : code.getCoding()) {
			if (coding == null || !coding.hasSystem() || !coding.hasCode()) {
				continue;
			}
			
			String system = coding.getSystem();
			String rawCode = coding.getCode();
			if (system == null || rawCode == null) {
				continue;
			}
			
			String trimmedCode = rawCode.trim();
			if (trimmedCode.isEmpty()) {
				continue;
			}
			
			ConceptSource conceptSource = fhirConceptSourceService.getConceptSourceByUrl(system).orElse(null);
			if (conceptSource == null) {
				continue;
			}
			
			// Check for existing mappings on this concept for this source
			boolean hasSameMappingOnConcept = false;
			boolean hasConflictingSameMappingOnConcept = false;
			if (concept.getConceptMappings() != null) {
				for (ConceptMap mapping : concept.getConceptMappings()) {
					if (mapping == null || mapping.getConceptReferenceTerm() == null
					        || mapping.getConceptReferenceTerm().getConceptSource() == null) {
						continue;
					}
					
					ConceptReferenceTerm term = mapping.getConceptReferenceTerm();
					if (!conceptSource.equals(term.getConceptSource())) {
						continue;
					}
					
					ConceptMapType mapType = mapping.getConceptMapType();
					boolean sameAs = mapType != null
					        && (ConceptMapType.SAME_AS_MAP_TYPE_UUID.equals(mapType.getUuid())
					                        || (mapType.getName() != null && mapType.getName().equalsIgnoreCase("SAME-AS")));
					if (!sameAs) {
						continue;
					}
					
					String existingCode = term.getCode();
					if (existingCode != null && existingCode.equalsIgnoreCase(trimmedCode)) {
						hasSameMappingOnConcept = true;
					} else {
						hasConflictingSameMappingOnConcept = true;
					}
				}
			}
			
			if (hasSameMappingOnConcept) {
				// Mapping already present; nothing to do
				continue;
			}
			
			if (hasConflictingSameMappingOnConcept) {
				String defnId = definition.hasId() ? definition.getIdElement().getIdPart() : null;
				log.warn(
				    "Skipping mapping code '{}' from system '{}' for ObservationDefinition {} because concept already has a different SAME-AS mapping for that source",
				    trimmedCode, system, defnId);
				continue;
			}
			
			// Ensure there is no SAME-AS mapping for this code on a different concept
			Concept mappedConcept = fhirConceptService.getConceptWithSameAsMappingInSource(conceptSource, trimmedCode)
			        .orElse(null);
			if (mappedConcept != null && !mappedConcept.equals(concept)) {
				String defnId = definition.hasId() ? definition.getIdElement().getIdPart() : null;
				log.warn(
				    "Skipping mapping code '{}' from system '{}' for ObservationDefinition {} because it is already mapped as SAME-AS to a different concept (uuid={})",
				    trimmedCode, system, defnId, mappedConcept.getUuid());
				continue;
			}
			
			ConceptMapType sameAsType = conceptService.getConceptMapTypeByUuid(ConceptMapType.SAME_AS_MAP_TYPE_UUID);
			if (sameAsType == null) {
				sameAsType = conceptService.getConceptMapTypeByName("SAME-AS");
			}
			if (sameAsType == null) {
				String defnId = definition.hasId() ? definition.getIdElement().getIdPart() : null;
				log.warn(
				    "Concept map type 'SAME-AS' not found; cannot create mapping for code '{}' from system '{}' on ObservationDefinition {}",
				    trimmedCode, system, defnId);
				continue;
			}
			
			ConceptReferenceTerm term = new ConceptReferenceTerm();
			term.setConceptSource(conceptSource);
			term.setCode(trimmedCode);
			
			String termName = coding.hasDisplay() ? coding.getDisplay() : null;
			if ((termName == null || termName.trim().isEmpty()) && code.hasText()) {
				termName = code.getText();
			}
			if (termName != null && !termName.trim().isEmpty()) {
				term.setName(termName.trim());
			}
			
			ConceptMap newMapping = new ConceptMap();
			newMapping.setConceptMapType(sameAsType);
			newMapping.setConceptReferenceTerm(term);
			concept.addConceptMapping(newMapping);
		}
	}
	
	private Concept findReusableTestConcept(ObservationDefinition definition) {
		if (definition == null) {
			return null;
		}
		
		String nameText = null;
		if (definition.getCode() != null && definition.getCode().hasText()) {
			nameText = definition.getCode().getText();
		}
		if (nameText == null && definition.getIdElement() != null) {
			nameText = definition.getIdElement().getIdPart();
		}
		if (nameText == null || nameText.trim().isEmpty()) {
			return null;
		}
		String trimmed = nameText.trim();
		
		Concept candidate = conceptService.getConceptByName(trimmed);
		if (candidate == null) {
			return null;
		}
		
		ConceptClass testClass = conceptService.getConceptClassByName("Test");
		if (testClass == null || !testClass.equals(candidate.getConceptClass())) {
			return null;
		}
		
		if (isNumericDefinition(definition)) {
			ConceptDatatype numericDatatype = conceptService.getConceptDatatypeByName("Numeric");
			if (numericDatatype == null || !numericDatatype.equals(candidate.getDatatype())) {
				return null;
			}
			if (!(candidate instanceof ConceptNumeric)) {
				return null;
			}
		} else if (isCodedDefinition(definition)) {
			ConceptDatatype codedDatatype = conceptService.getConceptDatatypeByName("Coded");
			if (codedDatatype == null || !codedDatatype.equals(candidate.getDatatype())) {
				return null;
			}
		} else {
			ConceptDatatype textDatatype = conceptService.getConceptDatatypeByName("Text");
			if (textDatatype == null || !textDatatype.equals(candidate.getDatatype())) {
				return null;
			}
		}
		
		return candidate;
	}
	
	private Concept createConceptSkeleton(ObservationDefinition definition) {
		if (isNumericDefinition(definition)) {
			return new ConceptNumeric();
		}
		return new Concept();
	}
	
	private boolean isNumericDefinition(ObservationDefinition definition) {
		if (definition == null || !definition.hasPermittedDataType()) {
			return false;
		}
		return definition.getPermittedDataType().stream()
		        .anyMatch(type -> type != null && type.getValue() == ObservationDataType.QUANTITY);
	}
	
	private boolean isCodedDefinition(ObservationDefinition definition) {
		if (definition == null || !definition.hasPermittedDataType()) {
			return false;
		}
		return definition.getPermittedDataType().stream()
		        .anyMatch(type -> type != null && type.getValue() == ObservationDataType.CODEABLECONCEPT);
	}
	
	private void applyNumericDetails(ConceptNumeric conceptNumeric, ObservationDefinition definition) {
		if (definition == null) {
			return;
		}
		
		ObservationDefinitionQuantitativeDetailsComponent quantitativeDetails = definition.getQuantitativeDetails();
		if (quantitativeDetails != null) {
			CodeableConcept unit = quantitativeDetails.getUnit();
			if (unit != null) {
				String unitCode = null;
				if (unit.hasCoding() && unit.getCodingFirstRep().hasCode()) {
					unitCode = unit.getCodingFirstRep().getCode();
				} else if (unit.hasText()) {
					unitCode = unit.getText();
				}
				if (unitCode != null && !unitCode.trim().isEmpty()) {
					conceptNumeric.setUnits(unitCode.trim());
				}
			}
			
			if (quantitativeDetails.hasDecimalPrecision()) {
				Integer decimalPrecision = quantitativeDetails.getDecimalPrecision();
				if (decimalPrecision != null && decimalPrecision == 0) {
					conceptNumeric.setAllowDecimal(false);
				} else {
					conceptNumeric.setAllowDecimal(true);
				}
			} else {
				conceptNumeric.setAllowDecimal(true);
			}
		}
		
		applyQualifiedIntervalsToConceptNumeric(conceptNumeric, definition);
	}
	
	private void populateNumericDefinition(ConceptNumeric conceptNumeric, ObservationDefinition definition) {
		definition.addPermittedDataType(ObservationDataType.QUANTITY);
		
		ObservationDefinitionQuantitativeDetailsComponent quantitativeDetails = new ObservationDefinitionQuantitativeDetailsComponent();
		
		if (conceptNumeric.getUnits() != null && !conceptNumeric.getUnits().trim().isEmpty()) {
			String units = conceptNumeric.getUnits().trim();
			CodeableConcept unit = new CodeableConcept();
			unit.addCoding().setCode(units).setDisplay(units);
			unit.setText(units);
			quantitativeDetails.setUnit(unit);
		}
		
		if (conceptNumeric.getAllowDecimal() != null && !conceptNumeric.getAllowDecimal()) {
			quantitativeDetails.setDecimalPrecision(0);
		}
		
		if (quantitativeDetails.hasUnit() || quantitativeDetails.hasDecimalPrecision()) {
			definition.setQuantitativeDetails(quantitativeDetails);
		}
	}
	
	private void populateNumericIntervals(ConceptNumeric conceptNumeric, ObservationDefinition definition) {
		if (conceptNumeric == null || definition == null) {
			return;
		}
		
		boolean allowDecimal = conceptNumeric.getAllowDecimal() != null ? conceptNumeric.getAllowDecimal() : true;
		
		if (conceptNumeric.getLowNormal() != null || conceptNumeric.getHiNormal() != null) {
			addQualifiedInterval(definition, conceptNumeric.getLowNormal(), conceptNumeric.getHiNormal(), allowDecimal,
			    FhirConstants.OBSERVATION_REFERENCE_NORMAL);
		}
		
		if (conceptNumeric.getLowCritical() != null || conceptNumeric.getHiCritical() != null) {
			addQualifiedInterval(definition, conceptNumeric.getLowCritical(), conceptNumeric.getHiCritical(), allowDecimal,
			    FhirConstants.OBSERVATION_REFERENCE_TREATMENT);
		}
		
		if (conceptNumeric.getLowAbsolute() != null || conceptNumeric.getHiAbsolute() != null) {
			addQualifiedInterval(definition, conceptNumeric.getLowAbsolute(), conceptNumeric.getHiAbsolute(), allowDecimal,
			    FhirConstants.OBSERVATION_REFERENCE_ABSOLUTE);
		}
	}
	
	private void addQualifiedInterval(ObservationDefinition definition, Double low, Double high, boolean allowDecimal,
	        String code) {
		ObservationDefinitionQualifiedIntervalComponent interval = definition.addQualifiedInterval();
		Range range = new Range();
		
		if (low != null) {
			Quantity qLow = new Quantity();
			if (allowDecimal) {
				qLow.setValue(low);
			} else {
				qLow.setValue(low.longValue());
			}
			range.setLow(qLow);
		}
		
		if (high != null) {
			Quantity qHigh = new Quantity();
			if (allowDecimal) {
				qHigh.setValue(high);
			} else {
				qHigh.setValue(high.longValue());
			}
			range.setHigh(qHigh);
		}
		
		interval.setRange(range);
		
		if (code != null) {
			interval.addExtension(FhirConstants.OPENMRS_FHIR_EXT_OBSERVATION_REFERENCE_RANGE, new CodeType(code));
		}
	}
	
	private void applyQualifiedIntervalsToConceptNumeric(ConceptNumeric conceptNumeric, ObservationDefinition definition) {
		if (definition == null || !definition.hasQualifiedInterval()) {
			return;
		}
		
		for (ObservationDefinitionQualifiedIntervalComponent interval : definition.getQualifiedInterval()) {
			if (interval == null || !interval.hasRange()) {
				continue;
			}
			
			Type extValue = null;
			if (interval.hasExtension(FhirConstants.OPENMRS_FHIR_EXT_OBSERVATION_REFERENCE_RANGE)) {
				extValue = interval.getExtensionByUrl(FhirConstants.OPENMRS_FHIR_EXT_OBSERVATION_REFERENCE_RANGE).getValue();
			}
			
			if (extValue == null) {
				continue;
			}
			
			String code = null;
			if (extValue instanceof CodeType) {
				code = ((CodeType) extValue).getValue();
			} else if (extValue.isPrimitive()) {
				code = extValue.primitiveValue();
			}
			
			if (code == null || code.trim().isEmpty()) {
				continue;
			}
			
			Range range = interval.getRange();
			if (range == null) {
				continue;
			}
			
			Double low = null;
			Double high = null;
			if (range.hasLow() && range.getLow().hasValue()) {
				low = range.getLow().getValue().doubleValue();
			}
			if (range.hasHigh() && range.getHigh().hasValue()) {
				high = range.getHigh().getValue().doubleValue();
			}
			
			if (FhirConstants.OBSERVATION_REFERENCE_NORMAL.equals(code)) {
				if (low != null) {
					conceptNumeric.setLowNormal(low);
				}
				if (high != null) {
					conceptNumeric.setHiNormal(high);
				}
			} else if (FhirConstants.OBSERVATION_REFERENCE_TREATMENT.equals(code)) {
				if (low != null) {
					conceptNumeric.setLowCritical(low);
				}
				if (high != null) {
					conceptNumeric.setHiCritical(high);
				}
			} else if (FhirConstants.OBSERVATION_REFERENCE_ABSOLUTE.equals(code)) {
				if (low != null) {
					conceptNumeric.setLowAbsolute(low);
				}
				if (high != null) {
					conceptNumeric.setHiAbsolute(high);
				}
			}
		}
	}
	
	private void applyAnswersFromValidCodedValueSet(Concept concept, ObservationDefinition definition) {
		if (concept == null || definition == null || !definition.hasValidCodedValueSet()) {
			return;
		}
		
		Reference vsRef = definition.getValidCodedValueSet();
		if (vsRef == null || !vsRef.hasReference()) {
			return;
		}
		
		String canonical = vsRef.getReference();
		if (canonical == null || canonical.trim().isEmpty()) {
			return;
		}
		
		String defnId = definition.hasId() && definition.getIdElement() != null ? definition.getIdElement().getIdPart()
		        : null;
		log.debug("applyAnswersFromValidCodedValueSet start: testConceptUuid={} defnId={} validCodedValueSetRef={}",
		    concept.getUuid(), defnId, canonical);
		
		String valueSetId = extractLocalValueSetId(canonical);
		if (valueSetId == null) {
			log.debug(
			    "applyAnswersFromValidCodedValueSet skipping: unresolved ValueSet reference canonical='{}' for ObservationDefinition {}",
			    canonical, defnId);
			return;
		}
		
		ValueSet valueSet;
		try {
			valueSet = fhirValueSetService.get(valueSetId);
		}
		catch (Exception ex) {
			log.warn(
			    "applyAnswersFromValidCodedValueSet failed to resolve ValueSet id='{}' (canonical='{}') for ObservationDefinition {}: {}",
			    valueSetId, canonical, defnId, ex.getMessage());
			return;
		}
		
		if (valueSet == null || !valueSet.hasCompose() || !valueSet.getCompose().hasInclude()) {
			log.debug(
			    "applyAnswersFromValidCodedValueSet no compose/include found for ValueSet id='{}' canonical='{}' defnId={}",
			    valueSetId, canonical, defnId);
			return;
		}
		
		int includeCount = 0;
		int conceptRefCount = 0;
		int attachedAnswerCount = 0;
		
		for (ValueSet.ConceptSetComponent include : valueSet.getCompose().getInclude()) {
			if (include == null || !include.hasConcept()) {
				continue;
			}
			includeCount++;
			
			// For now, only handle UUID-based includes without a system
			if (include.hasSystem()) {
				continue;
			}
			
			for (ValueSet.ConceptReferenceComponent conceptRef : include.getConcept()) {
				if (conceptRef == null || !conceptRef.hasCode()) {
					continue;
				}
				conceptRefCount++;
				
				String code = conceptRef.getCode();
				if (code == null || code.trim().isEmpty()) {
					continue;
				}
				String trimmedCode = code.trim();
				
				Concept answerConcept = conceptService.getConceptByUuid(trimmedCode);
				
				// If the concept does not exist yet, try to reuse an existing concept by name,
				// otherwise create a new Misc / N/A concept for this answer
				if (answerConcept == null) {
					String label = conceptRef.hasDisplay() ? conceptRef.getDisplay() : trimmedCode;
					if (label == null || label.trim().isEmpty()) {
						continue;
					}
					String candidateName = label.trim();
					Concept existingByName = conceptService.getConceptByName(candidateName);
					if (existingByName != null) {
						answerConcept = existingByName;
						log.debug(
						    "applyAnswersFromValidCodedValueSet reused existing answer concept by name: answerUuid={} name='{}' for testConceptUuid={} defnId={}",
						    answerConcept.getUuid(), candidateName, concept.getUuid(), defnId);
					} else {
						ConceptClass miscClass = conceptService.getConceptClassByName("Misc");
						if (miscClass == null) {
							throw new IllegalStateException("Concept class 'Misc' not found; cannot create answer concept");
						}
						ConceptDatatype naDatatype = conceptService.getConceptDatatypeByName("N/A");
						if (naDatatype == null) {
							throw new IllegalStateException(
							        "Concept datatype 'N/A' not found; cannot create answer concept");
						}
						
						Concept newAnswer = new Concept();
						newAnswer.setUuid(trimmedCode);
						newAnswer.setConceptClass(miscClass);
						newAnswer.setDatatype(naDatatype);
						
						ConceptName answerName = new ConceptName();
						answerName.setName(candidateName);
						answerName.setLocale(Context.getLocale());
						newAnswer.addName(answerName);
						
						answerConcept = conceptService.saveConcept(newAnswer);
						log.debug(
						    "applyAnswersFromValidCodedValueSet created new answer concept: answerUuid={} name='{}' for testConceptUuid={} defnId={}",
						    answerConcept.getUuid(), candidateName, concept.getUuid(), defnId);
					}
				}
				
				if (answerConcept == null || answerConcept.equals(concept)) {
					continue;
				}
				
				boolean alreadyAnswer = false;
				if (concept.getAnswers() != null) {
					for (ConceptAnswer existingAnswer : concept.getAnswers()) {
						if (answerConcept.equals(existingAnswer.getAnswerConcept())) {
							alreadyAnswer = true;
							break;
						}
					}
				}
				if (alreadyAnswer) {
					continue;
				}
				
				concept.addAnswer(new ConceptAnswer(answerConcept));
				attachedAnswerCount++;
				log.debug(
				    "applyAnswersFromValidCodedValueSet attached ConceptAnswer: testConceptUuid={} answerConceptUuid={} defnId={}",
				    concept.getUuid(), answerConcept.getUuid(), defnId);
			}
		}
		
		log.debug(
		    "applyAnswersFromValidCodedValueSet completed for testConceptUuid={} defnId={} valueSetId='{}' includes={} conceptRefs={} answersAttached={}",
		    concept.getUuid(), defnId, valueSetId, includeCount, conceptRefCount, attachedAnswerCount);
	}
	
	private String extractLocalValueSetId(String canonical) {
		String trimmed = canonical.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		
		// Primary supported form: explicit local reference ValueSet/{id}
		if (trimmed.startsWith("ValueSet/")) {
			return trimmed.substring("ValueSet/".length());
		}
		
		// Additional supported form: bare local URN used in the reference (e.g. "urn:uuid:vs-ladki-answers-1" or
		// "urn:uuid:e156d4ec-637d-4657-9c4d-f2f7b225963e"). In ELIS bundles, the underlying ValueSet.id is the
		// plain identifier (e.g. "vs-ladki-answers-1" or the GUID), while ObservationDefinition references it as
		// "urn:uuid:{id}". The FHIR2 ValueSet service exposes the resource at GET /ValueSet/{id}, so we must
		// strip the "urn:uuid:" prefix and return only the id portion.
		if (trimmed.startsWith("urn:uuid:")) {
			return trimmed.substring("urn:uuid:".length());
		}
		
		return null;
	}
}
