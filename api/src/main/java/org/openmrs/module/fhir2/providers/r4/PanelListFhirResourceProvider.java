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
import org.hl7.fhir.r4.model.ListResource;
import org.openmrs.module.fhir2.api.FhirPanelListService;
import org.openmrs.module.fhir2.api.annotations.R4Provider;
import org.openmrs.module.fhir2.providers.util.FhirProviderUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FHIR R4 resource provider for {@link ListResource} resources that represent OpenMRS lab panel
 * definitions (LabSet concepts with set members).
 */
@Slf4j
@Component("panelListFhirR4ResourceProvider")
@R4Provider
public class PanelListFhirResourceProvider implements IResourceProvider {
	
	@Getter(PROTECTED)
	@Setter(value = PACKAGE, onMethod_ = @Autowired)
	private FhirPanelListService panelListService;
	
	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return ListResource.class;
	}
	
	@Read
	public ListResource getPanelListById(@IdParam @Nonnull IdType id) {
		ListResource list = panelListService.get(id.getIdPart());
		if (list == null) {
			throw new ResourceNotFoundException("Could not find List (lab panel) with Id " + id.getIdPart());
		}
		return list;
	}
	
	@Create
	public MethodOutcome createPanelList(@ResourceParam ListResource list) {
		log.debug("Panel List @Create hasId={} idElement={}", list != null && list.hasId(),
		    list != null && list.getIdElement() != null ? list.getIdElement().getIdPart() : null);
		return FhirProviderUtils.buildCreate(panelListService.create(list));
	}
	
	@Update
	public MethodOutcome updatePanelList(@IdParam IdType id, @ResourceParam ListResource list) {
		if (id == null || id.getIdPart() == null) {
			throw new InvalidRequestException("id must be specified to update List (lab panel)");
		}
		
		log.debug("Panel List @Update urlId={} bodyHasId={} bodyId={}", id.getIdPart(), list != null && list.hasId(),
		    list != null && list.getIdElement() != null ? list.getIdElement().getIdPart() : null);
		
		// Always align the resource id with the URL id
		list.setId(id.getIdPart());
		
		// Upsert semantics, mirroring ObservationDefinition behavior
		try {
			return FhirProviderUtils.buildUpdate(panelListService.update(id.getIdPart(), list));
		}
		catch (ResourceNotFoundException e) {
			log.warn("Panel List @Update falling back to create for id={} because no existing resource was found",
			    id.getIdPart());
			return FhirProviderUtils.buildCreate(panelListService.create(list));
		}
	}
}
