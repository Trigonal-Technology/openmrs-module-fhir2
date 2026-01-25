/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.impl;

import static lombok.AccessLevel.PROTECTED;

import lombok.Getter;
import lombok.Setter;
import org.hl7.fhir.r4.model.ListResource;
import org.openmrs.Concept;
import org.openmrs.module.fhir2.api.FhirPanelListService;
import org.openmrs.module.fhir2.api.dao.FhirConceptDao;
import org.openmrs.module.fhir2.api.translators.PanelDefinitionTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link FhirPanelListService} backed by OpenMRS {@link Concept} lab
 * panel definitions (LabSet concepts).
 */
@Component
public class FhirPanelListServiceImpl extends BaseFhirService<ListResource, Concept> implements FhirPanelListService {
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private FhirConceptDao dao;
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private PanelDefinitionTranslator translator;
}
