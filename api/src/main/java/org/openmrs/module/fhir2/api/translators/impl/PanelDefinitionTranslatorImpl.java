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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.openmrs.Concept;
import org.openmrs.ConceptClass;
import org.openmrs.ConceptDatatype;
import org.openmrs.ConceptName;
import org.openmrs.ConceptSet;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.translators.PanelDefinitionTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PanelDefinitionTranslatorImpl implements PanelDefinitionTranslator {
	
	@Getter(PROTECTED)
	@Setter(value = PROTECTED, onMethod_ = @Autowired)
	private ConceptService conceptService;
	
	@Override
	public Concept toOpenmrsType(@Nonnull ListResource resource) {
		if (resource == null) {
			return null;
		}
		
		String id = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : null;
		Concept panel = null;
		if (id != null && !id.trim().isEmpty()) {
			panel = conceptService.getConceptByUuid(id.trim());
		}
		if (panel == null) {
			panel = new Concept();
			if (id != null && !id.trim().isEmpty()) {
				panel.setUuid(id.trim());
			}
		}
		
		panel.setSet(true);
		
		ConceptClass labSetClass = conceptService.getConceptClassByUuid(ConceptClass.LABSET_UUID);
		if (labSetClass == null) {
			labSetClass = conceptService.getConceptClassByName("LabSet");
		}
		if (labSetClass == null) {
			throw new IllegalStateException("Concept class 'LabSet' not found; cannot create lab panel concept");
		}
		panel.setConceptClass(labSetClass);
		
		if (panel.getDatatype() == null) {
			ConceptDatatype naDatatype = conceptService.getConceptDatatypeByName("N/A");
			if (naDatatype == null) {
				throw new IllegalStateException("Concept datatype 'N/A' not found; cannot create lab panel concept");
			}
			panel.setDatatype(naDatatype);
		}
		
		String title = resource.getTitle();
		if (title == null || title.trim().isEmpty()) {
			IdType idElement = resource.getIdElement();
			title = idElement != null ? idElement.getIdPart() : null;
		}
		applyOrUpdatePanelName(panel, title);
		
		Set<String> desiredMemberUuids = new HashSet<>();
		if (resource.hasEntry()) {
			for (ListResource.ListEntryComponent entry : resource.getEntry()) {
				if (entry == null || !entry.hasItem()) {
					continue;
				}
				Reference itemRef = entry.getItem();
				if (itemRef == null || !itemRef.hasReference()) {
					continue;
				}
				String ref = itemRef.getReference();
				if (ref == null || ref.trim().isEmpty()) {
					continue;
				}
				String trimmed = ref.trim();
				String testUuid = null;
				if (trimmed.startsWith("ObservationDefinition/")) {
					testUuid = trimmed.substring("ObservationDefinition/".length());
				} else if (trimmed.startsWith("urn:uuid:")) {
					testUuid = trimmed.substring("urn:uuid:".length());
				} else {
					continue;
				}
				if (testUuid == null || testUuid.trim().isEmpty()) {
					continue;
				}
				String guid = testUuid.trim();
				desiredMemberUuids.add(guid);
			}
		}
		
		Collection<ConceptSet> conceptSets = panel.getConceptSets();
		if (conceptSets != null && !conceptSets.isEmpty()) {
			conceptSets.removeIf(conceptSet -> {
				if (conceptSet == null) {
					return false;
				}
				Concept member = conceptSet.getConcept();
				String memberUuid = member != null ? member.getUuid() : null;
				return memberUuid != null && !desiredMemberUuids.contains(memberUuid);
			});
		}
		
		Set<String> currentMemberUuids = new HashSet<>();
		if (conceptSets != null) {
			for (ConceptSet conceptSet : conceptSets) {
				if (conceptSet == null) {
					continue;
				}
				Concept member = conceptSet.getConcept();
				if (member != null && member.getUuid() != null) {
					currentMemberUuids.add(member.getUuid());
				}
			}
		}
		
		for (String guid : desiredMemberUuids) {
			if (currentMemberUuids.contains(guid)) {
				continue;
			}
			Concept member = conceptService.getConceptByUuid(guid);
			if (member == null) {
				continue;
			}
			if (member.equals(panel)) {
				continue;
			}
			panel.addSetMember(member);
			currentMemberUuids.add(guid);
		}
		
		return panel;
	}
	
	private void applyOrUpdatePanelName(Concept panel, String title) {
		if (panel == null) {
			return;
		}
		if (title == null || title.trim().isEmpty()) {
			return;
		}
		String trimmed = title.trim();
		Locale locale = Context.getLocale();
		ConceptName primary = null;
		if (panel.getNames() != null) {
			for (ConceptName candidate : panel.getNames()) {
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
		panel.addName(name);
	}
	
	@Override
	public ListResource toFhirResource(@Nonnull Concept concept) {
		if (concept == null) {
			return null;
		}
		
		if (!Boolean.TRUE.equals(concept.getSet())) {
			return null;
		}
		
		ConceptClass conceptClass = concept.getConceptClass();
		if (conceptClass == null) {
			return null;
		}
		ConceptClass labSetClass = conceptService.getConceptClassByUuid(ConceptClass.LABSET_UUID);
		if (labSetClass == null) {
			labSetClass = conceptService.getConceptClassByName("LabSet");
		}
		if (labSetClass == null || !labSetClass.equals(conceptClass)) {
			return null;
		}
		
		ListResource list = new ListResource();
		list.setId(concept.getUuid());
		list.setTitle(concept.getName() != null ? concept.getName().getName() : "");
		list.setStatus(ListResource.ListStatus.CURRENT);
		
		List<Concept> members = concept.getSetMembers();
		if (members != null) {
			for (Concept member : members) {
				if (member == null || member.getUuid() == null) {
					continue;
				}
				ListResource.ListEntryComponent entry = list.addEntry();
				entry.setItem(new Reference("ObservationDefinition/" + member.getUuid()));
			}
		}
		
		return list;
	}
}
