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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemAnswerOptionComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.openmrs.Concept;
import org.openmrs.ConceptAnswer;
import org.openmrs.ConceptClass;
import org.openmrs.ConceptDatatype;
import org.openmrs.ConceptName;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.translators.QuestionnaireTestTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Minimal, conservative implementation of {@link QuestionnaireTestTranslator} for coded tests.
 * <ul>
 * <li>Maps a coded test {@link Concept} to a FHIR {@link Questionnaire} with a single CHOICE item
 * whose answer options correspond to {@link ConceptAnswer}s.</li>
 * <li>Maps a FHIR {@link Questionnaire} back to a coded test {@link Concept}, creating/reusing
 * answer concepts as needed and attaching them as {@link ConceptAnswer}s with diff-based
 * replacement semantics.</li>
 * </ul>
 */
@Slf4j
@Component
public class QuestionnaireTestTranslatorImpl implements QuestionnaireTestTranslator {
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private ConceptService conceptService;
	
	@Override
	public Questionnaire toFhirResource(@Nonnull Concept concept) {
		if (concept == null) {
			return null;
		}
		
		if (concept.getDatatype() == null || concept.getDatatype().getName() == null
		        || !"Coded".equalsIgnoreCase(concept.getDatatype().getName())) {
			return null;
		}
		
		Questionnaire questionnaire = new Questionnaire();
		questionnaire.setId(concept.getUuid());
		questionnaire.setStatus(Enumerations.PublicationStatus.ACTIVE);
		questionnaire.setTitle(concept.getDisplayString());
		
		QuestionnaireItemComponent item = new QuestionnaireItemComponent();
		item.setLinkId(concept.getUuid() != null ? concept.getUuid() : "coded-test");
		item.setText(concept.getDisplayString());
		item.setType(QuestionnaireItemType.CHOICE);
		
		if (concept.getAnswers() != null) {
			for (ConceptAnswer answer : concept.getAnswers()) {
				if (answer == null || answer.getAnswerConcept() == null || answer.getAnswerConcept().getUuid() == null) {
					continue;
				}
				Concept answerConcept = answer.getAnswerConcept();
				String answerUuid = answerConcept.getUuid();
				if (answerUuid == null || answerUuid.trim().isEmpty()) {
					continue;
				}
				String trimmedUuid = answerUuid.trim();
				
				QuestionnaireItemAnswerOptionComponent option = new QuestionnaireItemAnswerOptionComponent();
				Coding coding = new Coding();
				coding.setSystem("urn:uuid");
				coding.setCode(trimmedUuid);
				coding.setDisplay(answerConcept.getDisplayString());
				option.setValue(coding);
				item.addAnswerOption(option);
			}
		}
		
		questionnaire.addItem(item);
		return questionnaire;
	}
	
	@Override
	public Concept toOpenmrsType(@Nonnull Questionnaire questionnaire) {
		Concept concept = findOrCreateTestConcept(questionnaire);
		return toOpenmrsType(concept, questionnaire);
	}
	
	@Override
	public Concept toOpenmrsType(@Nonnull Concept existing, @Nonnull Questionnaire questionnaire) {
		if (existing == null || questionnaire == null) {
			return existing;
		}
		
		if (questionnaire.hasId() && questionnaire.getIdElement() != null) {
			String guid = questionnaire.getIdElement().getIdPart();
			if (guid != null && !guid.trim().isEmpty()) {
				String trimmedGuid = guid.trim();
				if (existing.getUuid() == null) {
					existing.setUuid(trimmedGuid);
				} else if (existing.getId() == null && !trimmedGuid.equals(existing.getUuid())) {
					existing.setUuid(trimmedGuid);
				}
			}
		}
		
		ConceptClass testClass = conceptService.getConceptClassByName("Test");
		if (testClass == null) {
			throw new IllegalStateException("Concept class 'Test' not found; cannot create coded test concept");
		}
		existing.setConceptClass(testClass);
		
		ConceptDatatype codedDatatype = conceptService.getConceptDatatypeByName("Coded");
		if (codedDatatype == null) {
			throw new IllegalStateException("Concept datatype 'Coded' not found; cannot create coded test concept");
		}
		existing.setDatatype(codedDatatype);
		
		String nameText = null;
		if (questionnaire.hasTitle()) {
			nameText = questionnaire.getTitle();
		}
		if ((nameText == null || nameText.trim().isEmpty()) && questionnaire.hasItem()
		        && !questionnaire.getItem().isEmpty()) {
			QuestionnaireItemComponent item = questionnaire.getItemFirstRep();
			if (item.hasText()) {
				nameText = item.getText();
			}
		}
		if ((nameText == null || nameText.trim().isEmpty()) && questionnaire.getIdElement() != null) {
			nameText = questionnaire.getIdElement().getIdPart();
		}
		
		if (nameText == null || nameText.trim().isEmpty()) {
			throw new IllegalStateException("Questionnaire must provide title, item text, or id to derive a concept name");
		}
		
		applyOrUpdatePrimaryName(existing, nameText.trim());
		applyAnswersFromQuestionnaire(existing, questionnaire);
		
		return existing;
	}
	
	private Concept findOrCreateTestConcept(Questionnaire questionnaire) {
		if (questionnaire == null || !questionnaire.hasIdElement()) {
			return new Concept();
		}
		
		String guid = questionnaire.getIdElement().getIdPart();
		if (guid == null || guid.trim().isEmpty()) {
			return new Concept();
		}
		
		String trimmedGuid = guid.trim();
		Concept existing = conceptService.getConceptByUuid(trimmedGuid);
		if (existing != null) {
			return existing;
		}
		
		Concept concept = new Concept();
		concept.setUuid(trimmedGuid);
		return concept;
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
	
	private void applyAnswersFromQuestionnaire(Concept concept, Questionnaire questionnaire) {
		if (concept == null || questionnaire == null || !questionnaire.hasItem() || questionnaire.getItem().isEmpty()) {
			return;
		}
		
		QuestionnaireItemComponent item = questionnaire.getItemFirstRep();
		if (item.getType() != QuestionnaireItemType.CHOICE || !item.hasAnswerOption()) {
			return;
		}
		
		Set<String> desiredAnswerUuids = new HashSet<>();
		Map<String, Concept> desiredAnswerConcepts = new HashMap<>();
		
		for (QuestionnaireItemAnswerOptionComponent option : item.getAnswerOption()) {
			if (option == null || !option.hasValueCoding()) {
				continue;
			}
			Coding coding = option.getValueCoding();
			if (coding == null || !coding.hasCode()) {
				continue;
			}
			String code = coding.getCode();
			if (code == null || code.trim().isEmpty()) {
				continue;
			}
			String trimmedCode = code.trim();
			
			Concept answerConcept = conceptService.getConceptByUuid(trimmedCode);
			
			if (answerConcept == null) {
				String label = coding.hasDisplay() ? coding.getDisplay() : trimmedCode;
				if (label == null || label.trim().isEmpty()) {
					continue;
				}
				String candidateName = label.trim();
				Concept existingByName = conceptService.getConceptByName(candidateName);
				if (existingByName != null) {
					answerConcept = existingByName;
					log.debug(
					    "applyAnswersFromQuestionnaire reused existing answer concept by name: answerUuid={} name='{}' for testConceptUuid={}",
					    answerConcept.getUuid(), candidateName, concept.getUuid());
				} else {
					ConceptClass miscClass = conceptService.getConceptClassByName("Misc");
					if (miscClass == null) {
						throw new IllegalStateException("Concept class 'Misc' not found; cannot create answer concept");
					}
					ConceptDatatype naDatatype = conceptService.getConceptDatatypeByName("N/A");
					if (naDatatype == null) {
						throw new IllegalStateException("Concept datatype 'N/A' not found; cannot create answer concept");
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
					    "applyAnswersFromQuestionnaire created new answer concept: answerUuid={} name='{}' for testConceptUuid={}",
					    answerConcept.getUuid(), candidateName, concept.getUuid());
				}
			}
			
			if (answerConcept == null || answerConcept.equals(concept)) {
				continue;
			}
			
			String answerUuid = answerConcept.getUuid();
			if (answerUuid == null || answerUuid.trim().isEmpty()) {
				continue;
			}
			String trimmedUuid = answerUuid.trim();
			desiredAnswerUuids.add(trimmedUuid);
			desiredAnswerConcepts.put(trimmedUuid, answerConcept);
		}
		
		if (concept.getAnswers() != null && !concept.getAnswers().isEmpty()) {
			concept.getAnswers().removeIf(existingAnswer -> {
				if (existingAnswer == null || existingAnswer.getAnswerConcept() == null) {
					return false;
				}
				Concept answerConcept = existingAnswer.getAnswerConcept();
				String uuid = answerConcept.getUuid();
				return uuid != null && !desiredAnswerUuids.contains(uuid);
			});
		}
		
		Set<String> currentAnswerUuids = new HashSet<>();
		if (concept.getAnswers() != null) {
			for (ConceptAnswer existingAnswer : concept.getAnswers()) {
				if (existingAnswer == null || existingAnswer.getAnswerConcept() == null
				        || existingAnswer.getAnswerConcept().getUuid() == null) {
					continue;
				}
				currentAnswerUuids.add(existingAnswer.getAnswerConcept().getUuid());
			}
		}
		
		int attachedAnswerCount = 0;
		for (String uuid : desiredAnswerUuids) {
			if (currentAnswerUuids.contains(uuid)) {
				continue;
			}
			Concept answerConcept = desiredAnswerConcepts.get(uuid);
			if (answerConcept == null) {
				continue;
			}
			concept.addAnswer(new ConceptAnswer(answerConcept));
			attachedAnswerCount++;
			log.debug("applyAnswersFromQuestionnaire attached ConceptAnswer: testConceptUuid={} answerConceptUuid={}",
			    concept.getUuid(), answerConcept.getUuid());
		}
		
		log.debug("applyAnswersFromQuestionnaire completed for testConceptUuid={} desiredAnswers={} answersAttached={}",
		    concept.getUuid(), desiredAnswerUuids.size(), attachedAnswerCount);
	}
}
