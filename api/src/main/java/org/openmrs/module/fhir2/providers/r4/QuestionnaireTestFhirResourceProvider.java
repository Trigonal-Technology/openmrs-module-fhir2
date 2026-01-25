/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.providers.r4;

import static lombok.AccessLevel.PACKAGE;
import static lombok.AccessLevel.PROTECTED;

import javax.annotation.Nonnull;

import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.openmrs.module.fhir2.api.FhirQuestionnaireTestService;
import org.openmrs.module.fhir2.api.annotations.R4Provider;
import org.openmrs.module.fhir2.providers.util.FhirProviderUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FHIR R4 resource provider for {@link Questionnaire} resources that represent coded test
 * definition concepts and their answers.
 */
@Slf4j
@Component("questionnaireTestFhirR4ResourceProvider")
@R4Provider
public class QuestionnaireTestFhirResourceProvider implements IResourceProvider {
	
	@Getter(PROTECTED)
	@Setter(value = PACKAGE, onMethod_ = @Autowired)
	private FhirQuestionnaireTestService questionnaireTestService;
	
	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return Questionnaire.class;
	}
	
	@Read
	public Questionnaire getQuestionnaireById(@IdParam @Nonnull IdType id) {
		Questionnaire questionnaire = questionnaireTestService.get(id.getIdPart());
		if (questionnaire == null) {
			throw new ResourceNotFoundException("Could not find Questionnaire with Id " + id.getIdPart());
		}
		return questionnaire;
	}
	
	@Create
	public MethodOutcome createQuestionnaire(@ResourceParam Questionnaire questionnaire) {
		log.warn("Questionnaire @Create hasId={} idElement={}", questionnaire != null && questionnaire.hasId(),
		    questionnaire != null && questionnaire.getIdElement() != null ? questionnaire.getIdElement().getIdPart() : null);
		return FhirProviderUtils.buildCreate(questionnaireTestService.create(questionnaire));
	}
	
	@Update
	public MethodOutcome updateQuestionnaire(@IdParam IdType id, @ResourceParam Questionnaire questionnaire) {
		if (id == null || id.getIdPart() == null) {
			throw new InvalidRequestException("id must be specified to update Questionnaire");
		}
		
		log.warn("Questionnaire @Update urlId={} bodyHasId={} bodyId={}", id.getIdPart(),
		    questionnaire != null && questionnaire.hasId(),
		    questionnaire != null && questionnaire.getIdElement() != null ? questionnaire.getIdElement().getIdPart() : null);
		
		// Always align the resource id with the URL id
		questionnaire.setId(id.getIdPart());
		
		// Upsert semantics:
		// - If a test with this UUID exists, perform a normal update.
		// - If not, fall back to create(), allowing clients to seed the UUID via PUT.
		try {
			return FhirProviderUtils.buildUpdate(questionnaireTestService.update(id.getIdPart(), questionnaire));
		}
		catch (ResourceNotFoundException e) {
			log.warn("Questionnaire @Update falling back to create for id={} because no existing resource was found",
			    id.getIdPart());
			return FhirProviderUtils.buildCreate(questionnaireTestService.create(questionnaire));
		}
	}
}
