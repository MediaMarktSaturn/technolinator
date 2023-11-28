package com.mediamarktsaturn.technolinator;

import io.quarkus.runtime.annotations.RegisterForReflection;
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
import org.cyclonedx.util.LicenseResolver;
import org.cyclonedx.util.ObjectLocator;
import org.cyclonedx.util.VersionJsonAnnotationIntrospector;
import org.cyclonedx.util.VersionXmlAnnotationIntrospector;
import org.cyclonedx.util.deserializer.ComponentWrapperDeserializer;
import org.cyclonedx.util.deserializer.DependencyDeserializer;
import org.cyclonedx.util.deserializer.ExtensionDeserializer;
import org.cyclonedx.util.deserializer.LicenseDeserializer;
import org.cyclonedx.util.deserializer.VulnerabilityDeserializer;
import org.cyclonedx.util.mixin.MixInBomReference;
import org.cyclonedx.util.serializer.ComponentWrapperSerializer;
import org.cyclonedx.util.serializer.CustomDateSerializer;
import org.cyclonedx.util.serializer.DependencySerializer;
import org.cyclonedx.util.serializer.ExtensibleTypesSerializer;
import org.cyclonedx.util.serializer.ExtensionSerializer;
import org.cyclonedx.util.serializer.ExternalReferenceSerializer;
import org.cyclonedx.util.serializer.LicenseChoiceSerializer;
import org.cyclonedx.util.serializer.TrimStringSerializer;

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
    Component.Scope.class,
    Component.Type.class,
    Component.class,
    ComponentWrapper.class,
    ComponentWrapperDeserializer.class,
    ComponentWrapperSerializer.class,
    Composition.Aggregate.class,
    Composition.class,
    Copyright.class,
    CustomDateSerializer.class,
    Dependency.class,
    DependencyDeserializer.class,
    DependencyList.class,
    DependencySerializer.class,
    Descendants.class,
    Diff.class,
    Evidence.class,
    ExtensibleElement.class,
    ExtensibleType.class,
    ExtensibleTypesSerializer.class,
    Extension.ExtensionType.class,
    Extension.class,
    ExtensionDeserializer.class,
    ExtensionDeserializer.class,
    ExtensionSerializer.class,
    ExternalReference.Type.class,
    ExternalReference.class,
    ExternalReferenceSerializer.class,
    Hash.Algorithm.class,
    Hash.class,
    IdentifiableActionType.class,
    Issue.Type.class,
    Issue.class,
    License.class,
    LicenseChoice.class,
    LicenseChoiceSerializer.class,
    LicenseDeserializer.class,
    LicenseResolver.class,
    Metadata.class,
    MixInBomReference.class,
    ObjectLocator.class,
    OrganizationalContact.class,
    OrganizationalEntity.class,
    Patch.Type.class,
    Patch.class,
    Pedigree.class,
    Property.class,
    ReleaseNotes.Notes.class,
    ReleaseNotes.Resolves.Type.class,
    ReleaseNotes.Resolves.class,
    ReleaseNotes.class,
    Service.class,
    ServiceData.Flow.class,
    ServiceData.class,
    Signature.Algorithm.class,
    Signature.PublicKey.Crv.class,
    Signature.PublicKey.Kty.class,
    Signature.PublicKey.class,
    Signature.class,
    Source.class,
    Swid.class,
    Tool.class,
    TrimStringSerializer.class,
    Variants.class,
    VersionJsonAnnotationIntrospector.class,
    VersionXmlAnnotationIntrospector.class,
    VulnerabilityDeserializer.class,
})
public class NativeReflectionConfigCyclonedxCoreJava {
}
