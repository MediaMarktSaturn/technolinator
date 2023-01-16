package com.mediamarktsaturn.ghbot;

import org.cyclonedx.model.Ancestors;
import org.cyclonedx.model.AttachmentText;
import org.cyclonedx.model.Attribute;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.BomReference;
import org.cyclonedx.model.Commit;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.ComponentWrapper;
import org.cyclonedx.model.Composition;
import org.cyclonedx.model.Copyright;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.DependencyList;
import org.cyclonedx.model.Descendants;
import org.cyclonedx.model.Diff;
import org.cyclonedx.model.Evidence;
import org.cyclonedx.model.ExtensibleElement;
import org.cyclonedx.model.ExtensibleType;
import org.cyclonedx.model.Extension;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.IdentifiableActionType;
import org.cyclonedx.model.Issue;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalContact;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.Patch;
import org.cyclonedx.model.Pedigree;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.ReleaseNotes;
import org.cyclonedx.model.Service;
import org.cyclonedx.model.ServiceData;
import org.cyclonedx.model.Signature;
import org.cyclonedx.model.Source;
import org.cyclonedx.model.Swid;
import org.cyclonedx.model.Tool;
import org.cyclonedx.model.Variants;
import org.cyclonedx.util.ComponentWrapperDeserializer;
import org.cyclonedx.util.ComponentWrapperSerializer;
import org.cyclonedx.util.CustomDateSerializer;
import org.cyclonedx.util.DependencyDeserializer;
import org.cyclonedx.util.DependencySerializer;
import org.cyclonedx.util.ExtensibleTypesSerializer;
import org.cyclonedx.util.ExtensionDeserializer;
import org.cyclonedx.util.ExtensionSerializer;
import org.cyclonedx.util.ExternalReferenceSerializer;
import org.cyclonedx.util.LicenseChoiceSerializer;
import org.cyclonedx.util.LicenseDeserializer;
import org.cyclonedx.util.LicenseResolver;
import org.cyclonedx.util.ObjectLocator;
import org.cyclonedx.util.TrimStringSerializer;
import org.cyclonedx.util.VersionJsonAnnotationIntrospector;
import org.cyclonedx.util.VersionXmlAnnotationIntrospector;
import org.cyclonedx.util.VulnerabilityDeserializer;
import org.cyclonedx.util.mixin.MixInBomReference;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native Image Reflection config for
 * org.cyclonedx:cyclonedx-core-java
 */
@RegisterForReflection(targets = {
    Ancestors.class,
    AttachmentText.class,
    Attribute.class,
    Bom.class,
    BomReference.class,
    Commit.class,
    Component.class,
    Component.Type.class,
    Component.Scope.class,
    ComponentWrapper.class,
    Composition.class,
    Composition.Aggregate.class,
    Copyright.class,
    Dependency.class,
    DependencyList.class,
    Descendants.class,
    Diff.class,
    Evidence.class,
    ExtensibleElement.class,
    ExtensibleType.class,
    Extension.class,
    Extension.ExtensionType.class,
    ExternalReference.class,
    ExternalReference.Type.class,
    Hash.class,
    Hash.Algorithm.class,
    IdentifiableActionType.class,
    Issue.class,
    Issue.Type.class,
    License.class,
    LicenseChoice.class,
    Metadata.class,
    OrganizationalContact.class,
    OrganizationalEntity.class,
    Patch.class,
    Patch.Type.class,
    Pedigree.class,
    Property.class,
    ReleaseNotes.class,
    ReleaseNotes.Notes.class,
    ReleaseNotes.Resolves.class,
    ReleaseNotes.Resolves.Type.class,
    Service.class,
    ServiceData.class,
    ServiceData.Flow.class,
    Signature.class,
    Signature.Algorithm.class,
    Signature.PublicKey.class,
    Signature.PublicKey.Crv.class,
    Signature.PublicKey.Kty.class,
    Source.class,
    Swid.class,
    Tool.class,
    Variants.class,
    ComponentWrapperDeserializer.class,
    ComponentWrapperSerializer.class,
    CustomDateSerializer.class,
    DependencyDeserializer.class,
    DependencySerializer.class,
    ExtensibleTypesSerializer.class,
    ExtensionDeserializer.class,
    ExtensionSerializer.class,
    ExternalReferenceSerializer.class,
    LicenseChoiceSerializer.class,
    LicenseDeserializer.class,
    LicenseResolver.class,
    ObjectLocator.class,
    TrimStringSerializer.class,
    VersionJsonAnnotationIntrospector.class,
    VersionXmlAnnotationIntrospector.class,
    VulnerabilityDeserializer.class,
    ExtensionDeserializer.class,
    MixInBomReference.class
})
public class NativeReflectionConfigCyclonedxCoreJava {
}
