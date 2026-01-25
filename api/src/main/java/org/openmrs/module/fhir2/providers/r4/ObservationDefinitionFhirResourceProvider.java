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
import org.hl7.fhir.r4.model.ObservationDefinition;
import org.openmrs.module.fhir2.api.FhirObservationDefinitionService;
import org.openmrs.module.fhir2.api.annotations.R4Provider;
import org.openmrs.module.fhir2.providers.util.FhirProviderUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FHIR R4 resource provider for {@link ObservationDefinition} resources that represent OpenMRS test
 * definition concepts.
 */
@Slf4j
@Component("observationDefinitionFhirR4ResourceProvider")
@R4Provider
public class ObservationDefinitionFhirResourceProvider implements IResourceProvider {
	
	@Getter(PROTECTED)
	@Setter(value = PACKAGE, onMethod_ = @Autowired)
	private FhirObservationDefinitionService testDefinitionService;
	
	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return ObservationDefinition.class;
	}
	
	@Read
	public ObservationDefinition getObservationDefinitionById(@IdParam @Nonnull IdType id) {
		ObservationDefinition definition = testDefinitionService.get(id.getIdPart());
		if (definition == null) {
			throw new ResourceNotFoundException("Could not find ObservationDefinition with Id " + id.getIdPart());
		}
		return definition;
	}
	
	@Create
	public MethodOutcome createObservationDefinition(@ResourceParam ObservationDefinition definition) {
		log.warn("ObservationDefinition @Create hasId={} idElement={}", definition != null && definition.hasId(),
		    definition != null && definition.getIdElement() != null ? definition.getIdElement().getIdPart() : null);
		return FhirProviderUtils.buildCreate(testDefinitionService.create(definition));
	}
	
	@Update
	public MethodOutcome updateObservationDefinition(@IdParam IdType id, @ResourceParam ObservationDefinition definition) {
		if (id == null || id.getIdPart() == null) {
			throw new InvalidRequestException("id must be specified to update ObservationDefinition");
		}
		
		log.warn("ObservationDefinition @Update urlId={} bodyHasId={} bodyId={}", id.getIdPart(),
		    definition != null && definition.hasId(),
		    definition != null && definition.getIdElement() != null ? definition.getIdElement().getIdPart() : null);
		
		// Always align the resource id with the URL id
		definition.setId(id.getIdPart());
		
		// Upsert semantics:
		// - If a test with this UUID exists, perform a normal update.
		// - If not, fall back to create(), allowing clients (e.g. ELIS) to seed the UUID via PUT.
		try {
			return FhirProviderUtils.buildUpdate(testDefinitionService.update(id.getIdPart(), definition));
		}
		catch (ResourceNotFoundException e) {
			log.warn("ObservationDefinition @Update falling back to create for id={} because no existing resource was found",
			    id.getIdPart());
			return FhirProviderUtils.buildCreate(testDefinitionService.create(definition));
		}
	}
}
