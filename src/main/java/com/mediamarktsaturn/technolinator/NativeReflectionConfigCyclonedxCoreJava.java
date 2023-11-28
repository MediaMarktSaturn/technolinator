package com.mediamarktsaturn.technolinator;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.cyclonedx.model.*;
import org.cyclonedx.util.*;
import org.cyclonedx.util.deserializer.*;
import org.cyclonedx.util.serializer.*;
import org.cyclonedx.util.mixin.MixInBomReference;

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
