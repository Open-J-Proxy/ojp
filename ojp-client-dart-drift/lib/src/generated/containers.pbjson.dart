// This is a generated file - do not edit.
//
// Generated from containers.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports
// ignore_for_file: unused_import

import 'dart:convert' as $convert;
import 'dart:core' as $core;
import 'dart:typed_data' as $typed_data;

@$core.Deprecated('Use containerDescriptor instead')
const Container$json = {
  '1': 'Container',
  '2': [
    {
      '1': 'value',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Value',
      '9': 0,
      '10': 'value'
    },
    {
      '1': 'object',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Object',
      '9': 0,
      '10': 'object'
    },
    {
      '1': 'array',
      '3': 3,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Array',
      '9': 0,
      '10': 'array'
    },
    {
      '1': 'properties',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Properties',
      '9': 0,
      '10': 'properties'
    },
  ],
  '8': [
    {'1': 'content'},
  ],
};

/// Descriptor for `Container`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List containerDescriptor = $convert.base64Decode(
    'CglDb250YWluZXISLwoFdmFsdWUYASABKAsyFy5vanAudHJhbnNwb3J0LnYxLlZhbHVlSABSBX'
    'ZhbHVlEjIKBm9iamVjdBgCIAEoCzIYLm9qcC50cmFuc3BvcnQudjEuT2JqZWN0SABSBm9iamVj'
    'dBIvCgVhcnJheRgDIAEoCzIXLm9qcC50cmFuc3BvcnQudjEuQXJyYXlIAFIFYXJyYXkSPgoKcH'
    'JvcGVydGllcxgEIAEoCzIcLm9qcC50cmFuc3BvcnQudjEuUHJvcGVydGllc0gAUgpwcm9wZXJ0'
    'aWVzQgkKB2NvbnRlbnQ=');

@$core.Deprecated('Use valueDescriptor instead')
const Value$json = {
  '1': 'Value',
  '2': [
    {'1': 's', '3': 1, '4': 1, '5': 9, '9': 0, '10': 's'},
    {'1': 'n', '3': 2, '4': 1, '5': 1, '9': 0, '10': 'n'},
    {'1': 'b', '3': 3, '4': 1, '5': 8, '9': 0, '10': 'b'},
    {
      '1': 'o',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Object',
      '9': 0,
      '10': 'o'
    },
    {
      '1': 'a',
      '3': 5,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Array',
      '9': 0,
      '10': 'a'
    },
    {
      '1': 'null_value',
      '3': 6,
      '4': 1,
      '5': 14,
      '6': '.google.protobuf.NullValue',
      '9': 0,
      '10': 'nullValue'
    },
  ],
  '8': [
    {'1': 'kind'},
  ],
};

/// Descriptor for `Value`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List valueDescriptor = $convert.base64Decode(
    'CgVWYWx1ZRIOCgFzGAEgASgJSABSAXMSDgoBbhgCIAEoAUgAUgFuEg4KAWIYAyABKAhIAFIBYh'
    'IoCgFvGAQgASgLMhgub2pwLnRyYW5zcG9ydC52MS5PYmplY3RIAFIBbxInCgFhGAUgASgLMhcu'
    'b2pwLnRyYW5zcG9ydC52MS5BcnJheUgAUgFhEjsKCm51bGxfdmFsdWUYBiABKA4yGi5nb29nbG'
    'UucHJvdG9idWYuTnVsbFZhbHVlSABSCW51bGxWYWx1ZUIGCgRraW5k');

@$core.Deprecated('Use objectDescriptor instead')
const Object$json = {
  '1': 'Object',
  '2': [
    {
      '1': 'entries',
      '3': 1,
      '4': 3,
      '5': 11,
      '6': '.ojp.transport.v1.Object.EntriesEntry',
      '10': 'entries'
    },
  ],
  '3': [Object_EntriesEntry$json],
};

@$core.Deprecated('Use objectDescriptor instead')
const Object_EntriesEntry$json = {
  '1': 'EntriesEntry',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
    {
      '1': 'value',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.ojp.transport.v1.Value',
      '10': 'value'
    },
  ],
  '7': {'7': true},
};

/// Descriptor for `Object`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List objectDescriptor = $convert.base64Decode(
    'CgZPYmplY3QSPwoHZW50cmllcxgBIAMoCzIlLm9qcC50cmFuc3BvcnQudjEuT2JqZWN0LkVudH'
    'JpZXNFbnRyeVIHZW50cmllcxpTCgxFbnRyaWVzRW50cnkSEAoDa2V5GAEgASgJUgNrZXkSLQoF'
    'dmFsdWUYAiABKAsyFy5vanAudHJhbnNwb3J0LnYxLlZhbHVlUgV2YWx1ZToCOAE=');

@$core.Deprecated('Use arrayDescriptor instead')
const Array$json = {
  '1': 'Array',
  '2': [
    {
      '1': 'values',
      '3': 1,
      '4': 3,
      '5': 11,
      '6': '.ojp.transport.v1.Value',
      '10': 'values'
    },
  ],
};

/// Descriptor for `Array`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List arrayDescriptor = $convert.base64Decode(
    'CgVBcnJheRIvCgZ2YWx1ZXMYASADKAsyFy5vanAudHJhbnNwb3J0LnYxLlZhbHVlUgZ2YWx1ZX'
    'M=');

@$core.Deprecated('Use propertiesDescriptor instead')
const Properties$json = {
  '1': 'Properties',
  '2': [
    {
      '1': 'entries',
      '3': 1,
      '4': 3,
      '5': 11,
      '6': '.ojp.transport.v1.Properties.EntriesEntry',
      '10': 'entries'
    },
  ],
  '3': [Properties_EntriesEntry$json],
};

@$core.Deprecated('Use propertiesDescriptor instead')
const Properties_EntriesEntry$json = {
  '1': 'EntriesEntry',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
    {'1': 'value', '3': 2, '4': 1, '5': 9, '10': 'value'},
  ],
  '7': {'7': true},
};

/// Descriptor for `Properties`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List propertiesDescriptor = $convert.base64Decode(
    'CgpQcm9wZXJ0aWVzEkMKB2VudHJpZXMYASADKAsyKS5vanAudHJhbnNwb3J0LnYxLlByb3Blcn'
    'RpZXMuRW50cmllc0VudHJ5UgdlbnRyaWVzGjoKDEVudHJpZXNFbnRyeRIQCgNrZXkYASABKAlS'
    'A2tleRIUCgV2YWx1ZRgCIAEoCVIFdmFsdWU6AjgB');
