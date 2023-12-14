package com.mediamarktsaturn.technolinator;

import com.networknt.schema.AdditionalPropertiesValidator;
import com.networknt.schema.AllOfValidator;
import com.networknt.schema.AnyOfValidator;
import com.networknt.schema.ConstValidator;
import com.networknt.schema.ContainsValidator;
import com.networknt.schema.DateTimeValidator;
import com.networknt.schema.DependenciesValidator;
import com.networknt.schema.DependentRequired;
import com.networknt.schema.DependentSchemas;
import com.networknt.schema.EnumValidator;
import com.networknt.schema.ExclusiveMaximumValidator;
import com.networknt.schema.ExclusiveMinimumValidator;
import com.networknt.schema.FalseValidator;
import com.networknt.schema.FormatValidator;
import com.networknt.schema.IfValidator;
import com.networknt.schema.ItemsValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.MaxItemsValidator;
import com.networknt.schema.MaxLengthValidator;
import com.networknt.schema.MaxPropertiesValidator;
import com.networknt.schema.MaximumValidator;
import com.networknt.schema.MinItemsValidator;
import com.networknt.schema.MinLengthValidator;
import com.networknt.schema.MinPropertiesValidator;
import com.networknt.schema.MinimumValidator;
import com.networknt.schema.MultipleOfValidator;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.NotAllowedValidator;
import com.networknt.schema.NotValidator;
import com.networknt.schema.OneOfValidator;
import com.networknt.schema.PatternPropertiesValidator;
import com.networknt.schema.PatternValidator;
import com.networknt.schema.PrefixItemsValidator;
import com.networknt.schema.PropertiesValidator;
import com.networknt.schema.PropertyNamesValidator;
import com.networknt.schema.ReadOnlyValidator;
import com.networknt.schema.RefValidator;
import com.networknt.schema.RequiredValidator;
import com.networknt.schema.TrueValidator;
import com.networknt.schema.TypeValidator;
import com.networknt.schema.UUIDValidator;
import com.networknt.schema.UnEvaluatedPropertiesValidator;
import com.networknt.schema.UnionTypeValidator;
import com.networknt.schema.UniqueItemsValidator;
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
