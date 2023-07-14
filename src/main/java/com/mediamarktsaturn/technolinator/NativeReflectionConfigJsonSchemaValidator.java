package com.mediamarktsaturn.technolinator;

import com.networknt.schema.*;
import com.networknt.schema.format.DurationValidator;
import com.networknt.schema.format.EmailValidator;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native Image Reflection config for
 * com.networknt:json-schema-validator
 */
@RegisterForReflection(targets = {
    PropertiesValidator.class,
    NonValidationKeyword.class,
    AdditionalPropertiesValidator.class,
    TypeValidator.class,
    RequiredValidator.class,
    EmailValidator.class,
    DurationValidator.class,
    AllOfValidator.class,
    AnyOfValidator.class,
    ConstValidator.class,
    ContainsValidator.class,
    DateTimeValidator.class,
    DependenciesValidator.class,
    DependentRequired.class,
    DependentSchemas.class,
    EnumValidator.class,
    ExclusiveMaximumValidator.class,
    ExclusiveMinimumValidator.class,
    FalseValidator.class,
    FormatValidator.class,
    IfValidator.class,
    ItemsValidator.class,
    JsonSchema.class,
    MaximumValidator.class,
    MaxItemsValidator.class,
    MaxLengthValidator.class,
    MaxPropertiesValidator.class,
    MinimumValidator.class,
    MinItemsValidator.class,
    MinLengthValidator.class,
    MinPropertiesValidator.class,
    MultipleOfValidator.class,
    NotAllowedValidator.class,
    NotValidator.class,
    OneOfValidator.class,
    PatternPropertiesValidator.class,
    PatternValidator.class,
    PrefixItemsValidator.class,
    PropertyNamesValidator.class,
    ReadOnlyValidator.class,
    RefValidator.class,
    TrueValidator.class,
    UnEvaluatedPropertiesValidator.class,
    UnionTypeValidator.class,
    UniqueItemsValidator.class,
    UUIDValidator.class
})
public class NativeReflectionConfigJsonSchemaValidator {
}
