// This is a generated file - do not edit.
//
// Generated from StatementService.proto.

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

@$core.Deprecated('Use parameterTypeProtoDescriptor instead')
const ParameterTypeProto$json = {
  '1': 'ParameterTypeProto',
  '2': [
    {'1': 'PT_NULL', '2': 0},
    {'1': 'PT_BOOLEAN', '2': 1},
    {'1': 'PT_BYTE', '2': 2},
    {'1': 'PT_SHORT', '2': 3},
    {'1': 'PT_INT', '2': 4},
    {'1': 'PT_LONG', '2': 5},
    {'1': 'PT_FLOAT', '2': 6},
    {'1': 'PT_DOUBLE', '2': 7},
    {'1': 'PT_BIG_DECIMAL', '2': 8},
    {'1': 'PT_STRING', '2': 9},
    {'1': 'PT_BYTES', '2': 10},
    {'1': 'PT_DATE', '2': 11},
    {'1': 'PT_TIME', '2': 12},
    {'1': 'PT_TIMESTAMP', '2': 13},
    {'1': 'PT_ASCII_STREAM', '2': 14},
    {'1': 'PT_UNICODE_STREAM', '2': 15},
    {'1': 'PT_BINARY_STREAM', '2': 16},
    {'1': 'PT_OBJECT', '2': 17},
    {'1': 'PT_CHARACTER_READER', '2': 18},
    {'1': 'PT_REF', '2': 19},
    {'1': 'PT_BLOB', '2': 20},
    {'1': 'PT_CLOB', '2': 21},
    {'1': 'PT_ARRAY', '2': 22},
    {'1': 'PT_URL', '2': 23},
    {'1': 'PT_ROW_ID', '2': 24},
    {'1': 'PT_N_STRING', '2': 25},
    {'1': 'PT_N_CHARACTER_STREAM', '2': 26},
    {'1': 'PT_N_CLOB', '2': 27},
    {'1': 'PT_SQL_XML', '2': 28},
  ],
};

/// Descriptor for `ParameterTypeProto`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List parameterTypeProtoDescriptor = $convert.base64Decode(
    'ChJQYXJhbWV0ZXJUeXBlUHJvdG8SCwoHUFRfTlVMTBAAEg4KClBUX0JPT0xFQU4QARILCgdQVF'
    '9CWVRFEAISDAoIUFRfU0hPUlQQAxIKCgZQVF9JTlQQBBILCgdQVF9MT05HEAUSDAoIUFRfRkxP'
    'QVQQBhINCglQVF9ET1VCTEUQBxISCg5QVF9CSUdfREVDSU1BTBAIEg0KCVBUX1NUUklORxAJEg'
    'wKCFBUX0JZVEVTEAoSCwoHUFRfREFURRALEgsKB1BUX1RJTUUQDBIQCgxQVF9USU1FU1RBTVAQ'
    'DRITCg9QVF9BU0NJSV9TVFJFQU0QDhIVChFQVF9VTklDT0RFX1NUUkVBTRAPEhQKEFBUX0JJTk'
    'FSWV9TVFJFQU0QEBINCglQVF9PQkpFQ1QQERIXChNQVF9DSEFSQUNURVJfUkVBREVSEBISCgoG'
    'UFRfUkVGEBMSCwoHUFRfQkxPQhAUEgsKB1BUX0NMT0IQFRIMCghQVF9BUlJBWRAWEgoKBlBUX1'
    'VSTBAXEg0KCVBUX1JPV19JRBAYEg8KC1BUX05fU1RSSU5HEBkSGQoVUFRfTl9DSEFSQUNURVJf'
    'U1RSRUFNEBoSDQoJUFRfTl9DTE9CEBsSDgoKUFRfU1FMX1hNTBAc');

@$core.Deprecated('Use temporalTypeDescriptor instead')
const TemporalType$json = {
  '1': 'TemporalType',
  '2': [
    {'1': 'TEMPORAL_TYPE_UNSPECIFIED', '2': 0},
    {'1': 'TEMPORAL_TYPE_TIMESTAMP', '2': 1},
    {'1': 'TEMPORAL_TYPE_CALENDAR', '2': 2},
    {'1': 'TEMPORAL_TYPE_OFFSET_DATE_TIME', '2': 3},
    {'1': 'TEMPORAL_TYPE_LOCAL_DATE_TIME', '2': 4},
    {'1': 'TEMPORAL_TYPE_INSTANT', '2': 5},
    {'1': 'TEMPORAL_TYPE_LOCAL_DATE', '2': 6},
    {'1': 'TEMPORAL_TYPE_LOCAL_TIME', '2': 7},
    {'1': 'TEMPORAL_TYPE_OFFSET_TIME', '2': 8},
  ],
};

/// Descriptor for `TemporalType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List temporalTypeDescriptor = $convert.base64Decode(
    'CgxUZW1wb3JhbFR5cGUSHQoZVEVNUE9SQUxfVFlQRV9VTlNQRUNJRklFRBAAEhsKF1RFTVBPUk'
    'FMX1RZUEVfVElNRVNUQU1QEAESGgoWVEVNUE9SQUxfVFlQRV9DQUxFTkRBUhACEiIKHlRFTVBP'
    'UkFMX1RZUEVfT0ZGU0VUX0RBVEVfVElNRRADEiEKHVRFTVBPUkFMX1RZUEVfTE9DQUxfREFURV'
    '9USU1FEAQSGQoVVEVNUE9SQUxfVFlQRV9JTlNUQU5UEAUSHAoYVEVNUE9SQUxfVFlQRV9MT0NB'
    'TF9EQVRFEAYSHAoYVEVNUE9SQUxfVFlQRV9MT0NBTF9USU1FEAcSHQoZVEVNUE9SQUxfVFlQRV'
    '9PRkZTRVRfVElNRRAI');

@$core.Deprecated('Use dbNameDescriptor instead')
const DbName$json = {
  '1': 'DbName',
  '2': [
    {'1': 'H2', '2': 0},
    {'1': 'MYSQL', '2': 1},
    {'1': 'MARIADB', '2': 2},
    {'1': 'POSTGRES', '2': 3},
    {'1': 'ORACLE', '2': 4},
    {'1': 'SQL_SERVER', '2': 5},
    {'1': 'DB2', '2': 6},
    {'1': 'UNMAPPED', '2': 7},
  ],
};

/// Descriptor for `DbName`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List dbNameDescriptor = $convert.base64Decode(
    'CgZEYk5hbWUSBgoCSDIQABIJCgVNWVNRTBABEgsKB01BUklBREIQAhIMCghQT1NUR1JFUxADEg'
    'oKBk9SQUNMRRAEEg4KClNRTF9TRVJWRVIQBRIHCgNEQjIQBhIMCghVTk1BUFBFRBAH');

@$core.Deprecated('Use sessionStatusDescriptor instead')
const SessionStatus$json = {
  '1': 'SessionStatus',
  '2': [
    {'1': 'SESSION_ACTIVE', '2': 0},
    {'1': 'SESSION_TERMINATED', '2': 1},
  ],
};

/// Descriptor for `SessionStatus`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List sessionStatusDescriptor = $convert.base64Decode(
    'Cg1TZXNzaW9uU3RhdHVzEhIKDlNFU1NJT05fQUNUSVZFEAASFgoSU0VTU0lPTl9URVJNSU5BVE'
    'VEEAE=');

@$core.Deprecated('Use transactionStatusDescriptor instead')
const TransactionStatus$json = {
  '1': 'TransactionStatus',
  '2': [
    {'1': 'TRX_ACTIVE', '2': 0},
    {'1': 'TRX_COMMITED', '2': 1},
    {'1': 'TRX_ROLLBACK', '2': 2},
  ],
};

/// Descriptor for `TransactionStatus`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List transactionStatusDescriptor = $convert.base64Decode(
    'ChFUcmFuc2FjdGlvblN0YXR1cxIOCgpUUlhfQUNUSVZFEAASEAoMVFJYX0NPTU1JVEVEEAESEA'
    'oMVFJYX1JPTExCQUNLEAI=');

@$core.Deprecated('Use resultTypeDescriptor instead')
const ResultType$json = {
  '1': 'ResultType',
  '2': [
    {'1': 'INTEGER', '2': 0},
    {'1': 'RESULT_SET_DATA', '2': 1},
    {'1': 'UUID_STRING', '2': 2},
  ],
};

/// Descriptor for `ResultType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List resultTypeDescriptor = $convert.base64Decode(
    'CgpSZXN1bHRUeXBlEgsKB0lOVEVHRVIQABITCg9SRVNVTFRfU0VUX0RBVEEQARIPCgtVVUlEX1'
    'NUUklORxAC');

@$core.Deprecated('Use sqlErrorTypeDescriptor instead')
const SqlErrorType$json = {
  '1': 'SqlErrorType',
  '2': [
    {'1': 'SQL_EXCEPTION', '2': 0},
    {'1': 'SQL_DATA_EXCEPTION', '2': 1},
    {'1': 'SQL_TRANSIENT_CONNECTION_EXCEPTION', '2': 2},
  ],
};

/// Descriptor for `SqlErrorType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List sqlErrorTypeDescriptor = $convert.base64Decode(
    'CgxTcWxFcnJvclR5cGUSEQoNU1FMX0VYQ0VQVElPThAAEhYKElNRTF9EQVRBX0VYQ0VQVElPTh'
    'ABEiYKIlNRTF9UUkFOU0lFTlRfQ09OTkVDVElPTl9FWENFUFRJT04QAg==');

@$core.Deprecated('Use lobTypeDescriptor instead')
const LobType$json = {
  '1': 'LobType',
  '2': [
    {'1': 'LT_BLOB', '2': 0},
    {'1': 'LT_CLOB', '2': 1},
    {'1': 'LT_BINARY_STREAM', '2': 2},
    {'1': 'LT_ASCII_STREAM', '2': 3},
    {'1': 'LT_UNICODE_STREAM', '2': 4},
    {'1': 'LT_CHARACTER_STREAM', '2': 5},
  ],
};

/// Descriptor for `LobType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List lobTypeDescriptor = $convert.base64Decode(
    'CgdMb2JUeXBlEgsKB0xUX0JMT0IQABILCgdMVF9DTE9CEAESFAoQTFRfQklOQVJZX1NUUkVBTR'
    'ACEhMKD0xUX0FTQ0lJX1NUUkVBTRADEhUKEUxUX1VOSUNPREVfU1RSRUFNEAQSFwoTTFRfQ0hB'
    'UkFDVEVSX1NUUkVBTRAF');

@$core.Deprecated('Use resourceTypeDescriptor instead')
const ResourceType$json = {
  '1': 'ResourceType',
  '2': [
    {'1': 'RES_RESULT_SET', '2': 0},
    {'1': 'RES_STATEMENT', '2': 1},
    {'1': 'RES_PREPARED_STATEMENT', '2': 2},
    {'1': 'RES_CALLABLE_STATEMENT', '2': 3},
    {'1': 'RES_LOB', '2': 4},
    {'1': 'RES_CONNECTION', '2': 5},
    {'1': 'RES_SAVEPOINT', '2': 6},
  ],
};

/// Descriptor for `ResourceType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List resourceTypeDescriptor = $convert.base64Decode(
    'CgxSZXNvdXJjZVR5cGUSEgoOUkVTX1JFU1VMVF9TRVQQABIRCg1SRVNfU1RBVEVNRU5UEAESGg'
    'oWUkVTX1BSRVBBUkVEX1NUQVRFTUVOVBACEhoKFlJFU19DQUxMQUJMRV9TVEFURU1FTlQQAxIL'
    'CgdSRVNfTE9CEAQSEgoOUkVTX0NPTk5FQ1RJT04QBRIRCg1SRVNfU0FWRVBPSU5UEAY=');

@$core.Deprecated('Use callTypeDescriptor instead')
const CallType$json = {
  '1': 'CallType',
  '2': [
    {'1': 'CALL_SET', '2': 0},
    {'1': 'CALL_GET', '2': 1},
    {'1': 'CALL_IS', '2': 2},
    {'1': 'CALL_ALL', '2': 3},
    {'1': 'CALL_NULLS', '2': 4},
    {'1': 'CALL_USES', '2': 5},
    {'1': 'CALL_SUPPORTS', '2': 6},
    {'1': 'CALL_STORES', '2': 7},
    {'1': 'CALL_NULL', '2': 8},
    {'1': 'CALL_DOES', '2': 9},
    {'1': 'CALL_DATA', '2': 10},
    {'1': 'CALL_NEXT', '2': 11},
    {'1': 'CALL_CLOSE', '2': 12},
    {'1': 'CALL_WAS', '2': 13},
    {'1': 'CALL_CLEAR', '2': 14},
    {'1': 'CALL_FIND', '2': 15},
    {'1': 'CALL_BEFORE', '2': 16},
    {'1': 'CALL_AFTER', '2': 17},
    {'1': 'CALL_FIRST', '2': 18},
    {'1': 'CALL_LAST', '2': 19},
    {'1': 'CALL_ABSOLUTE', '2': 20},
    {'1': 'CALL_RELATIVE', '2': 21},
    {'1': 'CALL_PREVIOUS', '2': 22},
    {'1': 'CALL_ROW', '2': 23},
    {'1': 'CALL_UPDATE', '2': 24},
    {'1': 'CALL_INSERT', '2': 25},
    {'1': 'CALL_DELETE', '2': 26},
    {'1': 'CALL_REFRESH', '2': 27},
    {'1': 'CALL_CANCEL', '2': 28},
    {'1': 'CALL_MOVE', '2': 29},
    {'1': 'CALL_OWN', '2': 30},
    {'1': 'CALL_OTHERS', '2': 31},
    {'1': 'CALL_UPDATES', '2': 32},
    {'1': 'CALL_DELETES', '2': 33},
    {'1': 'CALL_INSERTS', '2': 34},
    {'1': 'CALL_LOCATORS', '2': 35},
    {'1': 'CALL_AUTO', '2': 36},
    {'1': 'CALL_GENERATED', '2': 37},
    {'1': 'CALL_RELEASE', '2': 38},
    {'1': 'CALL_NATIVE', '2': 39},
    {'1': 'CALL_PREPARE', '2': 40},
    {'1': 'CALL_ROLLBACK', '2': 41},
    {'1': 'CALL_ABORT', '2': 42},
    {'1': 'CALL_EXECUTE', '2': 43},
    {'1': 'CALL_ADD', '2': 44},
    {'1': 'CALL_ENQUOTE', '2': 45},
    {'1': 'CALL_REGISTER', '2': 46},
    {'1': 'CALL_LENGTH', '2': 47},
  ],
};

/// Descriptor for `CallType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List callTypeDescriptor = $convert.base64Decode(
    'CghDYWxsVHlwZRIMCghDQUxMX1NFVBAAEgwKCENBTExfR0VUEAESCwoHQ0FMTF9JUxACEgwKCE'
    'NBTExfQUxMEAMSDgoKQ0FMTF9OVUxMUxAEEg0KCUNBTExfVVNFUxAFEhEKDUNBTExfU1VQUE9S'
    'VFMQBhIPCgtDQUxMX1NUT1JFUxAHEg0KCUNBTExfTlVMTBAIEg0KCUNBTExfRE9FUxAJEg0KCU'
    'NBTExfREFUQRAKEg0KCUNBTExfTkVYVBALEg4KCkNBTExfQ0xPU0UQDBIMCghDQUxMX1dBUxAN'
    'Eg4KCkNBTExfQ0xFQVIQDhINCglDQUxMX0ZJTkQQDxIPCgtDQUxMX0JFRk9SRRAQEg4KCkNBTE'
    'xfQUZURVIQERIOCgpDQUxMX0ZJUlNUEBISDQoJQ0FMTF9MQVNUEBMSEQoNQ0FMTF9BQlNPTFVU'
    'RRAUEhEKDUNBTExfUkVMQVRJVkUQFRIRCg1DQUxMX1BSRVZJT1VTEBYSDAoIQ0FMTF9ST1cQFx'
    'IPCgtDQUxMX1VQREFURRAYEg8KC0NBTExfSU5TRVJUEBkSDwoLQ0FMTF9ERUxFVEUQGhIQCgxD'
    'QUxMX1JFRlJFU0gQGxIPCgtDQUxMX0NBTkNFTBAcEg0KCUNBTExfTU9WRRAdEgwKCENBTExfT1'
    'dOEB4SDwoLQ0FMTF9PVEhFUlMQHxIQCgxDQUxMX1VQREFURVMQIBIQCgxDQUxMX0RFTEVURVMQ'
    'IRIQCgxDQUxMX0lOU0VSVFMQIhIRCg1DQUxMX0xPQ0FUT1JTECMSDQoJQ0FMTF9BVVRPECQSEg'
    'oOQ0FMTF9HRU5FUkFURUQQJRIQCgxDQUxMX1JFTEVBU0UQJhIPCgtDQUxMX05BVElWRRAnEhAK'
    'DENBTExfUFJFUEFSRRAoEhEKDUNBTExfUk9MTEJBQ0sQKRIOCgpDQUxMX0FCT1JUECoSEAoMQ0'
    'FMTF9FWEVDVVRFECsSDAoIQ0FMTF9BREQQLBIQCgxDQUxMX0VOUVVPVEUQLRIRCg1DQUxMX1JF'
    'R0lTVEVSEC4SDwoLQ0FMTF9MRU5HVEgQLw==');

@$core.Deprecated('Use connectionDetailsDescriptor instead')
const ConnectionDetails$json = {
  '1': 'ConnectionDetails',
  '2': [
    {'1': 'url', '3': 1, '4': 1, '5': 9, '10': 'url'},
    {'1': 'user', '3': 2, '4': 1, '5': 9, '10': 'user'},
    {'1': 'password', '3': 3, '4': 1, '5': 9, '10': 'password'},
    {'1': 'clientUUID', '3': 4, '4': 1, '5': 9, '10': 'clientUUID'},
    {
      '1': 'properties',
      '3': 6,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.PropertyEntry',
      '10': 'properties'
    },
    {'1': 'isXA', '3': 7, '4': 1, '5': 8, '10': 'isXA'},
    {'1': 'serverEndpoints', '3': 8, '4': 3, '5': 9, '10': 'serverEndpoints'},
    {'1': 'clusterHealth', '3': 9, '4': 1, '5': 9, '10': 'clusterHealth'},
  ],
};

/// Descriptor for `ConnectionDetails`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List connectionDetailsDescriptor = $convert.base64Decode(
    'ChFDb25uZWN0aW9uRGV0YWlscxIQCgN1cmwYASABKAlSA3VybBISCgR1c2VyGAIgASgJUgR1c2'
    'VyEhoKCHBhc3N3b3JkGAMgASgJUghwYXNzd29yZBIeCgpjbGllbnRVVUlEGAQgASgJUgpjbGll'
    'bnRVVUlEEkIKCnByb3BlcnRpZXMYBiADKAsyIi5jb20ub3Blbmpwcm94eS5ncnBjLlByb3Blcn'
    'R5RW50cnlSCnByb3BlcnRpZXMSEgoEaXNYQRgHIAEoCFIEaXNYQRIoCg9zZXJ2ZXJFbmRwb2lu'
    'dHMYCCADKAlSD3NlcnZlckVuZHBvaW50cxIkCg1jbHVzdGVySGVhbHRoGAkgASgJUg1jbHVzdG'
    'VySGVhbHRo');

@$core.Deprecated('Use timestampWithZoneDescriptor instead')
const TimestampWithZone$json = {
  '1': 'TimestampWithZone',
  '2': [
    {
      '1': 'instant',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.google.protobuf.Timestamp',
      '10': 'instant'
    },
    {'1': 'timezone', '3': 2, '4': 1, '5': 9, '10': 'timezone'},
    {
      '1': 'original_type',
      '3': 3,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.TemporalType',
      '10': 'originalType'
    },
  ],
};

/// Descriptor for `TimestampWithZone`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List timestampWithZoneDescriptor = $convert.base64Decode(
    'ChFUaW1lc3RhbXBXaXRoWm9uZRI0CgdpbnN0YW50GAEgASgLMhouZ29vZ2xlLnByb3RvYnVmLl'
    'RpbWVzdGFtcFIHaW5zdGFudBIaCgh0aW1lem9uZRgCIAEoCVIIdGltZXpvbmUSRgoNb3JpZ2lu'
    'YWxfdHlwZRgDIAEoDjIhLmNvbS5vcGVuanByb3h5LmdycGMuVGVtcG9yYWxUeXBlUgxvcmlnaW'
    '5hbFR5cGU=');

@$core.Deprecated('Use parameterValueDescriptor instead')
const ParameterValue$json = {
  '1': 'ParameterValue',
  '2': [
    {'1': 'bool_value', '3': 1, '4': 1, '5': 8, '9': 0, '10': 'boolValue'},
    {'1': 'int_value', '3': 2, '4': 1, '5': 5, '9': 0, '10': 'intValue'},
    {'1': 'long_value', '3': 3, '4': 1, '5': 3, '9': 0, '10': 'longValue'},
    {'1': 'float_value', '3': 4, '4': 1, '5': 2, '9': 0, '10': 'floatValue'},
    {'1': 'double_value', '3': 5, '4': 1, '5': 1, '9': 0, '10': 'doubleValue'},
    {'1': 'string_value', '3': 6, '4': 1, '5': 9, '9': 0, '10': 'stringValue'},
    {'1': 'bytes_value', '3': 7, '4': 1, '5': 12, '9': 0, '10': 'bytesValue'},
    {
      '1': 'int_array_value',
      '3': 8,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.IntArray',
      '9': 0,
      '10': 'intArrayValue'
    },
    {
      '1': 'long_array_value',
      '3': 9,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.LongArray',
      '9': 0,
      '10': 'longArrayValue'
    },
    {'1': 'is_null', '3': 10, '4': 1, '5': 8, '9': 0, '10': 'isNull'},
    {
      '1': 'timestamp_value',
      '3': 11,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.TimestampWithZone',
      '9': 0,
      '10': 'timestampValue'
    },
    {
      '1': 'date_value',
      '3': 12,
      '4': 1,
      '5': 11,
      '6': '.google.type.Date',
      '9': 0,
      '10': 'dateValue'
    },
    {
      '1': 'time_value',
      '3': 13,
      '4': 1,
      '5': 11,
      '6': '.google.type.TimeOfDay',
      '9': 0,
      '10': 'timeValue'
    },
    {
      '1': 'url_value',
      '3': 14,
      '4': 1,
      '5': 11,
      '6': '.google.protobuf.StringValue',
      '9': 0,
      '10': 'urlValue'
    },
    {
      '1': 'rowid_value',
      '3': 15,
      '4': 1,
      '5': 11,
      '6': '.google.protobuf.StringValue',
      '9': 0,
      '10': 'rowidValue'
    },
    {
      '1': 'uuid_value',
      '3': 16,
      '4': 1,
      '5': 11,
      '6': '.google.protobuf.StringValue',
      '9': 0,
      '10': 'uuidValue'
    },
    {
      '1': 'biginteger_value',
      '3': 17,
      '4': 1,
      '5': 11,
      '6': '.google.protobuf.StringValue',
      '9': 0,
      '10': 'bigintegerValue'
    },
    {
      '1': 'string_array_value',
      '3': 18,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.StringArray',
      '9': 0,
      '10': 'stringArrayValue'
    },
    {
      '1': 'rowidlifetime_value',
      '3': 19,
      '4': 1,
      '5': 11,
      '6': '.google.protobuf.StringValue',
      '9': 0,
      '10': 'rowidlifetimeValue'
    },
  ],
  '8': [
    {'1': 'value'},
  ],
};

/// Descriptor for `ParameterValue`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List parameterValueDescriptor = $convert.base64Decode(
    'Cg5QYXJhbWV0ZXJWYWx1ZRIfCgpib29sX3ZhbHVlGAEgASgISABSCWJvb2xWYWx1ZRIdCglpbn'
    'RfdmFsdWUYAiABKAVIAFIIaW50VmFsdWUSHwoKbG9uZ192YWx1ZRgDIAEoA0gAUglsb25nVmFs'
    'dWUSIQoLZmxvYXRfdmFsdWUYBCABKAJIAFIKZmxvYXRWYWx1ZRIjCgxkb3VibGVfdmFsdWUYBS'
    'ABKAFIAFILZG91YmxlVmFsdWUSIwoMc3RyaW5nX3ZhbHVlGAYgASgJSABSC3N0cmluZ1ZhbHVl'
    'EiEKC2J5dGVzX3ZhbHVlGAcgASgMSABSCmJ5dGVzVmFsdWUSRwoPaW50X2FycmF5X3ZhbHVlGA'
    'ggASgLMh0uY29tLm9wZW5qcHJveHkuZ3JwYy5JbnRBcnJheUgAUg1pbnRBcnJheVZhbHVlEkoK'
    'EGxvbmdfYXJyYXlfdmFsdWUYCSABKAsyHi5jb20ub3Blbmpwcm94eS5ncnBjLkxvbmdBcnJheU'
    'gAUg5sb25nQXJyYXlWYWx1ZRIZCgdpc19udWxsGAogASgISABSBmlzTnVsbBJRCg90aW1lc3Rh'
    'bXBfdmFsdWUYCyABKAsyJi5jb20ub3Blbmpwcm94eS5ncnBjLlRpbWVzdGFtcFdpdGhab25lSA'
    'BSDnRpbWVzdGFtcFZhbHVlEjIKCmRhdGVfdmFsdWUYDCABKAsyES5nb29nbGUudHlwZS5EYXRl'
    'SABSCWRhdGVWYWx1ZRI3Cgp0aW1lX3ZhbHVlGA0gASgLMhYuZ29vZ2xlLnR5cGUuVGltZU9mRG'
    'F5SABSCXRpbWVWYWx1ZRI7Cgl1cmxfdmFsdWUYDiABKAsyHC5nb29nbGUucHJvdG9idWYuU3Ry'
    'aW5nVmFsdWVIAFIIdXJsVmFsdWUSPwoLcm93aWRfdmFsdWUYDyABKAsyHC5nb29nbGUucHJvdG'
    '9idWYuU3RyaW5nVmFsdWVIAFIKcm93aWRWYWx1ZRI9Cgp1dWlkX3ZhbHVlGBAgASgLMhwuZ29v'
    'Z2xlLnByb3RvYnVmLlN0cmluZ1ZhbHVlSABSCXV1aWRWYWx1ZRJJChBiaWdpbnRlZ2VyX3ZhbH'
    'VlGBEgASgLMhwuZ29vZ2xlLnByb3RvYnVmLlN0cmluZ1ZhbHVlSABSD2JpZ2ludGVnZXJWYWx1'
    'ZRJQChJzdHJpbmdfYXJyYXlfdmFsdWUYEiABKAsyIC5jb20ub3Blbmpwcm94eS5ncnBjLlN0cm'
    'luZ0FycmF5SABSEHN0cmluZ0FycmF5VmFsdWUSTwoTcm93aWRsaWZldGltZV92YWx1ZRgTIAEo'
    'CzIcLmdvb2dsZS5wcm90b2J1Zi5TdHJpbmdWYWx1ZUgAUhJyb3dpZGxpZmV0aW1lVmFsdWVCBw'
    'oFdmFsdWU=');

@$core.Deprecated('Use intArrayDescriptor instead')
const IntArray$json = {
  '1': 'IntArray',
  '2': [
    {'1': 'values', '3': 1, '4': 3, '5': 5, '10': 'values'},
  ],
};

/// Descriptor for `IntArray`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List intArrayDescriptor =
    $convert.base64Decode('CghJbnRBcnJheRIWCgZ2YWx1ZXMYASADKAVSBnZhbHVlcw==');

@$core.Deprecated('Use longArrayDescriptor instead')
const LongArray$json = {
  '1': 'LongArray',
  '2': [
    {'1': 'values', '3': 1, '4': 3, '5': 3, '10': 'values'},
  ],
};

/// Descriptor for `LongArray`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List longArrayDescriptor =
    $convert.base64Decode('CglMb25nQXJyYXkSFgoGdmFsdWVzGAEgAygDUgZ2YWx1ZXM=');

@$core.Deprecated('Use stringArrayDescriptor instead')
const StringArray$json = {
  '1': 'StringArray',
  '2': [
    {'1': 'values', '3': 1, '4': 3, '5': 9, '10': 'values'},
  ],
};

/// Descriptor for `StringArray`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List stringArrayDescriptor = $convert
    .base64Decode('CgtTdHJpbmdBcnJheRIWCgZ2YWx1ZXMYASADKAlSBnZhbHVlcw==');

@$core.Deprecated('Use parameterProtoDescriptor instead')
const ParameterProto$json = {
  '1': 'ParameterProto',
  '2': [
    {'1': 'index', '3': 1, '4': 1, '5': 5, '10': 'index'},
    {
      '1': 'type',
      '3': 2,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.ParameterTypeProto',
      '10': 'type'
    },
    {
      '1': 'values',
      '3': 3,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.ParameterValue',
      '10': 'values'
    },
  ],
};

/// Descriptor for `ParameterProto`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List parameterProtoDescriptor = $convert.base64Decode(
    'Cg5QYXJhbWV0ZXJQcm90bxIUCgVpbmRleBgBIAEoBVIFaW5kZXgSOwoEdHlwZRgCIAEoDjInLm'
    'NvbS5vcGVuanByb3h5LmdycGMuUGFyYW1ldGVyVHlwZVByb3RvUgR0eXBlEjsKBnZhbHVlcxgD'
    'IAMoCzIjLmNvbS5vcGVuanByb3h5LmdycGMuUGFyYW1ldGVyVmFsdWVSBnZhbHVlcw==');

@$core.Deprecated('Use propertyEntryDescriptor instead')
const PropertyEntry$json = {
  '1': 'PropertyEntry',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
    {'1': 'bool_value', '3': 2, '4': 1, '5': 8, '9': 0, '10': 'boolValue'},
    {'1': 'int_value', '3': 3, '4': 1, '5': 5, '9': 0, '10': 'intValue'},
    {'1': 'long_value', '3': 4, '4': 1, '5': 3, '9': 0, '10': 'longValue'},
    {'1': 'float_value', '3': 5, '4': 1, '5': 2, '9': 0, '10': 'floatValue'},
    {'1': 'double_value', '3': 6, '4': 1, '5': 1, '9': 0, '10': 'doubleValue'},
    {'1': 'string_value', '3': 7, '4': 1, '5': 9, '9': 0, '10': 'stringValue'},
    {'1': 'bytes_value', '3': 8, '4': 1, '5': 12, '9': 0, '10': 'bytesValue'},
  ],
  '8': [
    {'1': 'value'},
  ],
};

/// Descriptor for `PropertyEntry`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List propertyEntryDescriptor = $convert.base64Decode(
    'Cg1Qcm9wZXJ0eUVudHJ5EhAKA2tleRgBIAEoCVIDa2V5Eh8KCmJvb2xfdmFsdWUYAiABKAhIAF'
    'IJYm9vbFZhbHVlEh0KCWludF92YWx1ZRgDIAEoBUgAUghpbnRWYWx1ZRIfCgpsb25nX3ZhbHVl'
    'GAQgASgDSABSCWxvbmdWYWx1ZRIhCgtmbG9hdF92YWx1ZRgFIAEoAkgAUgpmbG9hdFZhbHVlEi'
    'MKDGRvdWJsZV92YWx1ZRgGIAEoAUgAUgtkb3VibGVWYWx1ZRIjCgxzdHJpbmdfdmFsdWUYByAB'
    'KAlIAFILc3RyaW5nVmFsdWUSIQoLYnl0ZXNfdmFsdWUYCCABKAxIAFIKYnl0ZXNWYWx1ZUIHCg'
    'V2YWx1ZQ==');

@$core.Deprecated('Use resultRowDescriptor instead')
const ResultRow$json = {
  '1': 'ResultRow',
  '2': [
    {
      '1': 'columns',
      '3': 1,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.ParameterValue',
      '10': 'columns'
    },
  ],
};

/// Descriptor for `ResultRow`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List resultRowDescriptor = $convert.base64Decode(
    'CglSZXN1bHRSb3cSPQoHY29sdW1ucxgBIAMoCzIjLmNvbS5vcGVuanByb3h5LmdycGMuUGFyYW'
    '1ldGVyVmFsdWVSB2NvbHVtbnM=');

@$core.Deprecated('Use opQueryResultProtoDescriptor instead')
const OpQueryResultProto$json = {
  '1': 'OpQueryResultProto',
  '2': [
    {'1': 'resultSetUUID', '3': 1, '4': 1, '5': 9, '10': 'resultSetUUID'},
    {'1': 'labels', '3': 2, '4': 3, '5': 9, '10': 'labels'},
    {
      '1': 'rows',
      '3': 3,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.ResultRow',
      '10': 'rows'
    },
  ],
};

/// Descriptor for `OpQueryResultProto`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List opQueryResultProtoDescriptor = $convert.base64Decode(
    'ChJPcFF1ZXJ5UmVzdWx0UHJvdG8SJAoNcmVzdWx0U2V0VVVJRBgBIAEoCVINcmVzdWx0U2V0VV'
    'VJRBIWCgZsYWJlbHMYAiADKAlSBmxhYmVscxIyCgRyb3dzGAMgAygLMh4uY29tLm9wZW5qcHJv'
    'eHkuZ3JwYy5SZXN1bHRSb3dSBHJvd3M=');

@$core.Deprecated('Use transactionInfoDescriptor instead')
const TransactionInfo$json = {
  '1': 'TransactionInfo',
  '2': [
    {'1': 'transactionUUID', '3': 1, '4': 1, '5': 9, '10': 'transactionUUID'},
    {
      '1': 'transactionStatus',
      '3': 2,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.TransactionStatus',
      '10': 'transactionStatus'
    },
  ],
};

/// Descriptor for `TransactionInfo`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List transactionInfoDescriptor = $convert.base64Decode(
    'Cg9UcmFuc2FjdGlvbkluZm8SKAoPdHJhbnNhY3Rpb25VVUlEGAEgASgJUg90cmFuc2FjdGlvbl'
    'VVSUQSVAoRdHJhbnNhY3Rpb25TdGF0dXMYAiABKA4yJi5jb20ub3Blbmpwcm94eS5ncnBjLlRy'
    'YW5zYWN0aW9uU3RhdHVzUhF0cmFuc2FjdGlvblN0YXR1cw==');

@$core.Deprecated('Use sessionInfoDescriptor instead')
const SessionInfo$json = {
  '1': 'SessionInfo',
  '2': [
    {'1': 'connHash', '3': 1, '4': 1, '5': 9, '10': 'connHash'},
    {'1': 'clientUUID', '3': 2, '4': 1, '5': 9, '10': 'clientUUID'},
    {'1': 'sessionUUID', '3': 3, '4': 1, '5': 9, '10': 'sessionUUID'},
    {
      '1': 'transactionInfo',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.TransactionInfo',
      '10': 'transactionInfo'
    },
    {
      '1': 'sessionStatus',
      '3': 5,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.SessionStatus',
      '10': 'sessionStatus'
    },
    {'1': 'isXA', '3': 6, '4': 1, '5': 8, '10': 'isXA'},
    {'1': 'targetServer', '3': 7, '4': 1, '5': 9, '10': 'targetServer'},
    {'1': 'clusterHealth', '3': 8, '4': 1, '5': 9, '10': 'clusterHealth'},
    {'1': 'clientCount', '3': 9, '4': 1, '5': 5, '10': 'clientCount'},
    {'1': 'maxAdmission', '3': 10, '4': 1, '5': 5, '10': 'maxAdmission'},
    {'1': 'observedPeak', '3': 11, '4': 1, '5': 5, '10': 'observedPeak'},
  ],
};

/// Descriptor for `SessionInfo`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List sessionInfoDescriptor = $convert.base64Decode(
    'CgtTZXNzaW9uSW5mbxIaCghjb25uSGFzaBgBIAEoCVIIY29ubkhhc2gSHgoKY2xpZW50VVVJRB'
    'gCIAEoCVIKY2xpZW50VVVJRBIgCgtzZXNzaW9uVVVJRBgDIAEoCVILc2Vzc2lvblVVSUQSTgoP'
    'dHJhbnNhY3Rpb25JbmZvGAQgASgLMiQuY29tLm9wZW5qcHJveHkuZ3JwYy5UcmFuc2FjdGlvbk'
    'luZm9SD3RyYW5zYWN0aW9uSW5mbxJICg1zZXNzaW9uU3RhdHVzGAUgASgOMiIuY29tLm9wZW5q'
    'cHJveHkuZ3JwYy5TZXNzaW9uU3RhdHVzUg1zZXNzaW9uU3RhdHVzEhIKBGlzWEEYBiABKAhSBG'
    'lzWEESIgoMdGFyZ2V0U2VydmVyGAcgASgJUgx0YXJnZXRTZXJ2ZXISJAoNY2x1c3RlckhlYWx0'
    'aBgIIAEoCVINY2x1c3RlckhlYWx0aBIgCgtjbGllbnRDb3VudBgJIAEoBVILY2xpZW50Q291bn'
    'QSIgoMbWF4QWRtaXNzaW9uGAogASgFUgxtYXhBZG1pc3Npb24SIgoMb2JzZXJ2ZWRQZWFrGAsg'
    'ASgFUgxvYnNlcnZlZFBlYWs=');

@$core.Deprecated('Use opResultDescriptor instead')
const OpResult$json = {
  '1': 'OpResult',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'type',
      '3': 2,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.ResultType',
      '10': 'type'
    },
    {'1': 'int_value', '3': 3, '4': 1, '5': 5, '9': 0, '10': 'intValue'},
    {
      '1': 'query_result',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.OpQueryResultProto',
      '9': 0,
      '10': 'queryResult'
    },
    {'1': 'uuid_value', '3': 5, '4': 1, '5': 9, '9': 0, '10': 'uuidValue'},
    {'1': 'uuid', '3': 6, '4': 1, '5': 9, '10': 'uuid'},
    {'1': 'flag', '3': 7, '4': 1, '5': 9, '10': 'flag'},
  ],
  '8': [
    {'1': 'result'},
  ],
};

/// Descriptor for `OpResult`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List opResultDescriptor = $convert.base64Decode(
    'CghPcFJlc3VsdBI6CgdzZXNzaW9uGAEgASgLMiAuY29tLm9wZW5qcHJveHkuZ3JwYy5TZXNzaW'
    '9uSW5mb1IHc2Vzc2lvbhIzCgR0eXBlGAIgASgOMh8uY29tLm9wZW5qcHJveHkuZ3JwYy5SZXN1'
    'bHRUeXBlUgR0eXBlEh0KCWludF92YWx1ZRgDIAEoBUgAUghpbnRWYWx1ZRJMCgxxdWVyeV9yZX'
    'N1bHQYBCABKAsyJy5jb20ub3Blbmpwcm94eS5ncnBjLk9wUXVlcnlSZXN1bHRQcm90b0gAUgtx'
    'dWVyeVJlc3VsdBIfCgp1dWlkX3ZhbHVlGAUgASgJSABSCXV1aWRWYWx1ZRISCgR1dWlkGAYgAS'
    'gJUgR1dWlkEhIKBGZsYWcYByABKAlSBGZsYWdCCAoGcmVzdWx0');

@$core.Deprecated('Use statementRequestDescriptor instead')
const StatementRequest$json = {
  '1': 'StatementRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'sql', '3': 2, '4': 1, '5': 9, '10': 'sql'},
    {
      '1': 'parameters',
      '3': 3,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.ParameterProto',
      '10': 'parameters'
    },
    {'1': 'statementUUID', '3': 4, '4': 1, '5': 9, '10': 'statementUUID'},
    {
      '1': 'properties',
      '3': 5,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.PropertyEntry',
      '10': 'properties'
    },
  ],
};

/// Descriptor for `StatementRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List statementRequestDescriptor = $convert.base64Decode(
    'ChBTdGF0ZW1lbnRSZXF1ZXN0EjoKB3Nlc3Npb24YASABKAsyIC5jb20ub3Blbmpwcm94eS5ncn'
    'BjLlNlc3Npb25JbmZvUgdzZXNzaW9uEhAKA3NxbBgCIAEoCVIDc3FsEkMKCnBhcmFtZXRlcnMY'
    'AyADKAsyIy5jb20ub3Blbmpwcm94eS5ncnBjLlBhcmFtZXRlclByb3RvUgpwYXJhbWV0ZXJzEi'
    'QKDXN0YXRlbWVudFVVSUQYBCABKAlSDXN0YXRlbWVudFVVSUQSQgoKcHJvcGVydGllcxgFIAMo'
    'CzIiLmNvbS5vcGVuanByb3h5LmdycGMuUHJvcGVydHlFbnRyeVIKcHJvcGVydGllcw==');

@$core.Deprecated('Use sqlErrorResponseDescriptor instead')
const SqlErrorResponse$json = {
  '1': 'SqlErrorResponse',
  '2': [
    {'1': 'reason', '3': 1, '4': 1, '5': 9, '10': 'reason'},
    {'1': 'sqlState', '3': 2, '4': 1, '5': 9, '10': 'sqlState'},
    {'1': 'vendorCode', '3': 3, '4': 1, '5': 5, '10': 'vendorCode'},
    {
      '1': 'sqlErrorType',
      '3': 4,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.SqlErrorType',
      '10': 'sqlErrorType'
    },
  ],
};

/// Descriptor for `SqlErrorResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List sqlErrorResponseDescriptor = $convert.base64Decode(
    'ChBTcWxFcnJvclJlc3BvbnNlEhYKBnJlYXNvbhgBIAEoCVIGcmVhc29uEhoKCHNxbFN0YXRlGA'
    'IgASgJUghzcWxTdGF0ZRIeCgp2ZW5kb3JDb2RlGAMgASgFUgp2ZW5kb3JDb2RlEkUKDHNxbEVy'
    'cm9yVHlwZRgEIAEoDjIhLmNvbS5vcGVuanByb3h5LmdycGMuU3FsRXJyb3JUeXBlUgxzcWxFcn'
    'JvclR5cGU=');

@$core.Deprecated('Use lobReferenceDescriptor instead')
const LobReference$json = {
  '1': 'LobReference',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'uuid', '3': 2, '4': 1, '5': 9, '10': 'uuid'},
    {'1': 'bytesWritten', '3': 3, '4': 1, '5': 5, '10': 'bytesWritten'},
    {
      '1': 'lobType',
      '3': 4,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.LobType',
      '10': 'lobType'
    },
    {'1': 'columnIndex', '3': 5, '4': 1, '5': 5, '10': 'columnIndex'},
    {'1': 'stmtUUID', '3': 6, '4': 1, '5': 9, '10': 'stmtUUID'},
  ],
};

/// Descriptor for `LobReference`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List lobReferenceDescriptor = $convert.base64Decode(
    'CgxMb2JSZWZlcmVuY2USOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS5vcGVuanByb3h5LmdycGMuU2'
    'Vzc2lvbkluZm9SB3Nlc3Npb24SEgoEdXVpZBgCIAEoCVIEdXVpZBIiCgxieXRlc1dyaXR0ZW4Y'
    'AyABKAVSDGJ5dGVzV3JpdHRlbhI2Cgdsb2JUeXBlGAQgASgOMhwuY29tLm9wZW5qcHJveHkuZ3'
    'JwYy5Mb2JUeXBlUgdsb2JUeXBlEiAKC2NvbHVtbkluZGV4GAUgASgFUgtjb2x1bW5JbmRleBIa'
    'CghzdG10VVVJRBgGIAEoCVIIc3RtdFVVSUQ=');

@$core.Deprecated('Use readLobRequestDescriptor instead')
const ReadLobRequest$json = {
  '1': 'ReadLobRequest',
  '2': [
    {
      '1': 'lobReference',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.LobReference',
      '10': 'lobReference'
    },
    {'1': 'position', '3': 2, '4': 1, '5': 3, '10': 'position'},
    {'1': 'length', '3': 3, '4': 1, '5': 5, '10': 'length'},
  ],
};

/// Descriptor for `ReadLobRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List readLobRequestDescriptor = $convert.base64Decode(
    'Cg5SZWFkTG9iUmVxdWVzdBJFCgxsb2JSZWZlcmVuY2UYASABKAsyIS5jb20ub3Blbmpwcm94eS'
    '5ncnBjLkxvYlJlZmVyZW5jZVIMbG9iUmVmZXJlbmNlEhoKCHBvc2l0aW9uGAIgASgDUghwb3Np'
    'dGlvbhIWCgZsZW5ndGgYAyABKAVSBmxlbmd0aA==');

@$core.Deprecated('Use lobDataBlockDescriptor instead')
const LobDataBlock$json = {
  '1': 'LobDataBlock',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'position', '3': 2, '4': 1, '5': 3, '10': 'position'},
    {'1': 'data', '3': 3, '4': 1, '5': 12, '10': 'data'},
    {
      '1': 'lobType',
      '3': 4,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.LobType',
      '10': 'lobType'
    },
    {
      '1': 'metadata',
      '3': 5,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.PropertyEntry',
      '10': 'metadata'
    },
  ],
};

/// Descriptor for `LobDataBlock`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List lobDataBlockDescriptor = $convert.base64Decode(
    'CgxMb2JEYXRhQmxvY2sSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS5vcGVuanByb3h5LmdycGMuU2'
    'Vzc2lvbkluZm9SB3Nlc3Npb24SGgoIcG9zaXRpb24YAiABKANSCHBvc2l0aW9uEhIKBGRhdGEY'
    'AyABKAxSBGRhdGESNgoHbG9iVHlwZRgEIAEoDjIcLmNvbS5vcGVuanByb3h5LmdycGMuTG9iVH'
    'lwZVIHbG9iVHlwZRI+CghtZXRhZGF0YRgFIAMoCzIiLmNvbS5vcGVuanByb3h5LmdycGMuUHJv'
    'cGVydHlFbnRyeVIIbWV0YWRhdGE=');

@$core.Deprecated('Use sessionTerminationStatusDescriptor instead')
const SessionTerminationStatus$json = {
  '1': 'SessionTerminationStatus',
  '2': [
    {'1': 'terminated', '3': 1, '4': 1, '5': 8, '10': 'terminated'},
  ],
};

/// Descriptor for `SessionTerminationStatus`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List sessionTerminationStatusDescriptor =
    $convert.base64Decode(
        'ChhTZXNzaW9uVGVybWluYXRpb25TdGF0dXMSHgoKdGVybWluYXRlZBgBIAEoCFIKdGVybWluYX'
        'RlZA==');

@$core.Deprecated('Use targetCallDescriptor instead')
const TargetCall$json = {
  '1': 'TargetCall',
  '2': [
    {
      '1': 'callType',
      '3': 1,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.CallType',
      '10': 'callType'
    },
    {'1': 'resourceName', '3': 2, '4': 1, '5': 9, '10': 'resourceName'},
    {
      '1': 'params',
      '3': 3,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.ParameterValue',
      '10': 'params'
    },
    {
      '1': 'nextCall',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.TargetCall',
      '10': 'nextCall'
    },
  ],
};

/// Descriptor for `TargetCall`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List targetCallDescriptor = $convert.base64Decode(
    'CgpUYXJnZXRDYWxsEjkKCGNhbGxUeXBlGAEgASgOMh0uY29tLm9wZW5qcHJveHkuZ3JwYy5DYW'
    'xsVHlwZVIIY2FsbFR5cGUSIgoMcmVzb3VyY2VOYW1lGAIgASgJUgxyZXNvdXJjZU5hbWUSOwoG'
    'cGFyYW1zGAMgAygLMiMuY29tLm9wZW5qcHJveHkuZ3JwYy5QYXJhbWV0ZXJWYWx1ZVIGcGFyYW'
    '1zEjsKCG5leHRDYWxsGAQgASgLMh8uY29tLm9wZW5qcHJveHkuZ3JwYy5UYXJnZXRDYWxsUghu'
    'ZXh0Q2FsbA==');

@$core.Deprecated('Use callResourceRequestDescriptor instead')
const CallResourceRequest$json = {
  '1': 'CallResourceRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'resourceType',
      '3': 2,
      '4': 1,
      '5': 14,
      '6': '.com.openjproxy.grpc.ResourceType',
      '10': 'resourceType'
    },
    {'1': 'resourceUUID', '3': 3, '4': 1, '5': 9, '10': 'resourceUUID'},
    {
      '1': 'target',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.TargetCall',
      '10': 'target'
    },
    {
      '1': 'properties',
      '3': 5,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.PropertyEntry',
      '10': 'properties'
    },
  ],
};

/// Descriptor for `CallResourceRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List callResourceRequestDescriptor = $convert.base64Decode(
    'ChNDYWxsUmVzb3VyY2VSZXF1ZXN0EjoKB3Nlc3Npb24YASABKAsyIC5jb20ub3Blbmpwcm94eS'
    '5ncnBjLlNlc3Npb25JbmZvUgdzZXNzaW9uEkUKDHJlc291cmNlVHlwZRgCIAEoDjIhLmNvbS5v'
    'cGVuanByb3h5LmdycGMuUmVzb3VyY2VUeXBlUgxyZXNvdXJjZVR5cGUSIgoMcmVzb3VyY2VVVU'
    'lEGAMgASgJUgxyZXNvdXJjZVVVSUQSNwoGdGFyZ2V0GAQgASgLMh8uY29tLm9wZW5qcHJveHku'
    'Z3JwYy5UYXJnZXRDYWxsUgZ0YXJnZXQSQgoKcHJvcGVydGllcxgFIAMoCzIiLmNvbS5vcGVuan'
    'Byb3h5LmdycGMuUHJvcGVydHlFbnRyeVIKcHJvcGVydGllcw==');

@$core.Deprecated('Use callResourceResponseDescriptor instead')
const CallResourceResponse$json = {
  '1': 'CallResourceResponse',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'resourceUUID', '3': 2, '4': 1, '5': 9, '10': 'resourceUUID'},
    {
      '1': 'values',
      '3': 3,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.ParameterValue',
      '10': 'values'
    },
  ],
};

/// Descriptor for `CallResourceResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List callResourceResponseDescriptor = $convert.base64Decode(
    'ChRDYWxsUmVzb3VyY2VSZXNwb25zZRI6CgdzZXNzaW9uGAEgASgLMiAuY29tLm9wZW5qcHJveH'
    'kuZ3JwYy5TZXNzaW9uSW5mb1IHc2Vzc2lvbhIiCgxyZXNvdXJjZVVVSUQYAiABKAlSDHJlc291'
    'cmNlVVVJRBI7CgZ2YWx1ZXMYAyADKAsyIy5jb20ub3Blbmpwcm94eS5ncnBjLlBhcmFtZXRlcl'
    'ZhbHVlUgZ2YWx1ZXM=');

@$core.Deprecated('Use resultSetFetchRequestDescriptor instead')
const ResultSetFetchRequest$json = {
  '1': 'ResultSetFetchRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'resultSetUUID', '3': 2, '4': 1, '5': 9, '10': 'resultSetUUID'},
    {'1': 'size', '3': 3, '4': 1, '5': 5, '10': 'size'},
  ],
};

/// Descriptor for `ResultSetFetchRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List resultSetFetchRequestDescriptor = $convert.base64Decode(
    'ChVSZXN1bHRTZXRGZXRjaFJlcXVlc3QSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS5vcGVuanByb3'
    'h5LmdycGMuU2Vzc2lvbkluZm9SB3Nlc3Npb24SJAoNcmVzdWx0U2V0VVVJRBgCIAEoCVINcmVz'
    'dWx0U2V0VVVJRBISCgRzaXplGAMgASgFUgRzaXpl');

@$core.Deprecated('Use xidProtoDescriptor instead')
const XidProto$json = {
  '1': 'XidProto',
  '2': [
    {'1': 'formatId', '3': 1, '4': 1, '5': 5, '10': 'formatId'},
    {
      '1': 'globalTransactionId',
      '3': 2,
      '4': 1,
      '5': 12,
      '10': 'globalTransactionId'
    },
    {'1': 'branchQualifier', '3': 3, '4': 1, '5': 12, '10': 'branchQualifier'},
  ],
};

/// Descriptor for `XidProto`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xidProtoDescriptor = $convert.base64Decode(
    'CghYaWRQcm90bxIaCghmb3JtYXRJZBgBIAEoBVIIZm9ybWF0SWQSMAoTZ2xvYmFsVHJhbnNhY3'
    'Rpb25JZBgCIAEoDFITZ2xvYmFsVHJhbnNhY3Rpb25JZBIoCg9icmFuY2hRdWFsaWZpZXIYAyAB'
    'KAxSD2JyYW5jaFF1YWxpZmllcg==');

@$core.Deprecated('Use xaStartRequestDescriptor instead')
const XaStartRequest$json = {
  '1': 'XaStartRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xid',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xid'
    },
    {'1': 'flags', '3': 3, '4': 1, '5': 5, '10': 'flags'},
  ],
};

/// Descriptor for `XaStartRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaStartRequestDescriptor = $convert.base64Decode(
    'Cg5YYVN0YXJ0UmVxdWVzdBI6CgdzZXNzaW9uGAEgASgLMiAuY29tLm9wZW5qcHJveHkuZ3JwYy'
    '5TZXNzaW9uSW5mb1IHc2Vzc2lvbhIvCgN4aWQYAiABKAsyHS5jb20ub3Blbmpwcm94eS5ncnBj'
    'LlhpZFByb3RvUgN4aWQSFAoFZmxhZ3MYAyABKAVSBWZsYWdz');

@$core.Deprecated('Use xaEndRequestDescriptor instead')
const XaEndRequest$json = {
  '1': 'XaEndRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xid',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xid'
    },
    {'1': 'flags', '3': 3, '4': 1, '5': 5, '10': 'flags'},
  ],
};

/// Descriptor for `XaEndRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaEndRequestDescriptor = $convert.base64Decode(
    'CgxYYUVuZFJlcXVlc3QSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS5vcGVuanByb3h5LmdycGMuU2'
    'Vzc2lvbkluZm9SB3Nlc3Npb24SLwoDeGlkGAIgASgLMh0uY29tLm9wZW5qcHJveHkuZ3JwYy5Y'
    'aWRQcm90b1IDeGlkEhQKBWZsYWdzGAMgASgFUgVmbGFncw==');

@$core.Deprecated('Use xaPrepareRequestDescriptor instead')
const XaPrepareRequest$json = {
  '1': 'XaPrepareRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xid',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xid'
    },
  ],
};

/// Descriptor for `XaPrepareRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaPrepareRequestDescriptor = $convert.base64Decode(
    'ChBYYVByZXBhcmVSZXF1ZXN0EjoKB3Nlc3Npb24YASABKAsyIC5jb20ub3Blbmpwcm94eS5ncn'
    'BjLlNlc3Npb25JbmZvUgdzZXNzaW9uEi8KA3hpZBgCIAEoCzIdLmNvbS5vcGVuanByb3h5Lmdy'
    'cGMuWGlkUHJvdG9SA3hpZA==');

@$core.Deprecated('Use xaPrepareResponseDescriptor instead')
const XaPrepareResponse$json = {
  '1': 'XaPrepareResponse',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'result', '3': 2, '4': 1, '5': 5, '10': 'result'},
  ],
};

/// Descriptor for `XaPrepareResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaPrepareResponseDescriptor = $convert.base64Decode(
    'ChFYYVByZXBhcmVSZXNwb25zZRI6CgdzZXNzaW9uGAEgASgLMiAuY29tLm9wZW5qcHJveHkuZ3'
    'JwYy5TZXNzaW9uSW5mb1IHc2Vzc2lvbhIWCgZyZXN1bHQYAiABKAVSBnJlc3VsdA==');

@$core.Deprecated('Use xaCommitRequestDescriptor instead')
const XaCommitRequest$json = {
  '1': 'XaCommitRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xid',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xid'
    },
    {'1': 'onePhase', '3': 3, '4': 1, '5': 8, '10': 'onePhase'},
  ],
};

/// Descriptor for `XaCommitRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaCommitRequestDescriptor = $convert.base64Decode(
    'Cg9YYUNvbW1pdFJlcXVlc3QSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS5vcGVuanByb3h5LmdycG'
    'MuU2Vzc2lvbkluZm9SB3Nlc3Npb24SLwoDeGlkGAIgASgLMh0uY29tLm9wZW5qcHJveHkuZ3Jw'
    'Yy5YaWRQcm90b1IDeGlkEhoKCG9uZVBoYXNlGAMgASgIUghvbmVQaGFzZQ==');

@$core.Deprecated('Use xaRollbackRequestDescriptor instead')
const XaRollbackRequest$json = {
  '1': 'XaRollbackRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xid',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xid'
    },
  ],
};

/// Descriptor for `XaRollbackRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaRollbackRequestDescriptor = $convert.base64Decode(
    'ChFYYVJvbGxiYWNrUmVxdWVzdBI6CgdzZXNzaW9uGAEgASgLMiAuY29tLm9wZW5qcHJveHkuZ3'
    'JwYy5TZXNzaW9uSW5mb1IHc2Vzc2lvbhIvCgN4aWQYAiABKAsyHS5jb20ub3Blbmpwcm94eS5n'
    'cnBjLlhpZFByb3RvUgN4aWQ=');

@$core.Deprecated('Use xaRecoverRequestDescriptor instead')
const XaRecoverRequest$json = {
  '1': 'XaRecoverRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'flag', '3': 2, '4': 1, '5': 5, '10': 'flag'},
  ],
};

/// Descriptor for `XaRecoverRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaRecoverRequestDescriptor = $convert.base64Decode(
    'ChBYYVJlY292ZXJSZXF1ZXN0EjoKB3Nlc3Npb24YASABKAsyIC5jb20ub3Blbmpwcm94eS5ncn'
    'BjLlNlc3Npb25JbmZvUgdzZXNzaW9uEhIKBGZsYWcYAiABKAVSBGZsYWc=');

@$core.Deprecated('Use xaRecoverResponseDescriptor instead')
const XaRecoverResponse$json = {
  '1': 'XaRecoverResponse',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xids',
      '3': 2,
      '4': 3,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xids'
    },
  ],
};

/// Descriptor for `XaRecoverResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaRecoverResponseDescriptor = $convert.base64Decode(
    'ChFYYVJlY292ZXJSZXNwb25zZRI6CgdzZXNzaW9uGAEgASgLMiAuY29tLm9wZW5qcHJveHkuZ3'
    'JwYy5TZXNzaW9uSW5mb1IHc2Vzc2lvbhIxCgR4aWRzGAIgAygLMh0uY29tLm9wZW5qcHJveHku'
    'Z3JwYy5YaWRQcm90b1IEeGlkcw==');

@$core.Deprecated('Use xaForgetRequestDescriptor instead')
const XaForgetRequest$json = {
  '1': 'XaForgetRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {
      '1': 'xid',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.XidProto',
      '10': 'xid'
    },
  ],
};

/// Descriptor for `XaForgetRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaForgetRequestDescriptor = $convert.base64Decode(
    'Cg9YYUZvcmdldFJlcXVlc3QSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS5vcGVuanByb3h5LmdycG'
    'MuU2Vzc2lvbkluZm9SB3Nlc3Npb24SLwoDeGlkGAIgASgLMh0uY29tLm9wZW5qcHJveHkuZ3Jw'
    'Yy5YaWRQcm90b1IDeGlk');

@$core.Deprecated('Use xaSetTransactionTimeoutRequestDescriptor instead')
const XaSetTransactionTimeoutRequest$json = {
  '1': 'XaSetTransactionTimeoutRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'seconds', '3': 2, '4': 1, '5': 5, '10': 'seconds'},
  ],
};

/// Descriptor for `XaSetTransactionTimeoutRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaSetTransactionTimeoutRequestDescriptor =
    $convert.base64Decode(
        'Ch5YYVNldFRyYW5zYWN0aW9uVGltZW91dFJlcXVlc3QSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS'
        '5vcGVuanByb3h5LmdycGMuU2Vzc2lvbkluZm9SB3Nlc3Npb24SGAoHc2Vjb25kcxgCIAEoBVIH'
        'c2Vjb25kcw==');

@$core.Deprecated('Use xaSetTransactionTimeoutResponseDescriptor instead')
const XaSetTransactionTimeoutResponse$json = {
  '1': 'XaSetTransactionTimeoutResponse',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'success', '3': 2, '4': 1, '5': 8, '10': 'success'},
  ],
};

/// Descriptor for `XaSetTransactionTimeoutResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaSetTransactionTimeoutResponseDescriptor =
    $convert.base64Decode(
        'Ch9YYVNldFRyYW5zYWN0aW9uVGltZW91dFJlc3BvbnNlEjoKB3Nlc3Npb24YASABKAsyIC5jb2'
        '0ub3Blbmpwcm94eS5ncnBjLlNlc3Npb25JbmZvUgdzZXNzaW9uEhgKB3N1Y2Nlc3MYAiABKAhS'
        'B3N1Y2Nlc3M=');

@$core.Deprecated('Use xaGetTransactionTimeoutRequestDescriptor instead')
const XaGetTransactionTimeoutRequest$json = {
  '1': 'XaGetTransactionTimeoutRequest',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
  ],
};

/// Descriptor for `XaGetTransactionTimeoutRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaGetTransactionTimeoutRequestDescriptor =
    $convert.base64Decode(
        'Ch5YYUdldFRyYW5zYWN0aW9uVGltZW91dFJlcXVlc3QSOgoHc2Vzc2lvbhgBIAEoCzIgLmNvbS'
        '5vcGVuanByb3h5LmdycGMuU2Vzc2lvbkluZm9SB3Nlc3Npb24=');

@$core.Deprecated('Use xaGetTransactionTimeoutResponseDescriptor instead')
const XaGetTransactionTimeoutResponse$json = {
  '1': 'XaGetTransactionTimeoutResponse',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'seconds', '3': 2, '4': 1, '5': 5, '10': 'seconds'},
  ],
};

/// Descriptor for `XaGetTransactionTimeoutResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaGetTransactionTimeoutResponseDescriptor =
    $convert.base64Decode(
        'Ch9YYUdldFRyYW5zYWN0aW9uVGltZW91dFJlc3BvbnNlEjoKB3Nlc3Npb24YASABKAsyIC5jb2'
        '0ub3Blbmpwcm94eS5ncnBjLlNlc3Npb25JbmZvUgdzZXNzaW9uEhgKB3NlY29uZHMYAiABKAVS'
        'B3NlY29uZHM=');

@$core.Deprecated('Use xaIsSameRMRequestDescriptor instead')
const XaIsSameRMRequest$json = {
  '1': 'XaIsSameRMRequest',
  '2': [
    {
      '1': 'session1',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session1'
    },
    {
      '1': 'session2',
      '3': 2,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session2'
    },
  ],
};

/// Descriptor for `XaIsSameRMRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaIsSameRMRequestDescriptor = $convert.base64Decode(
    'ChFYYUlzU2FtZVJNUmVxdWVzdBI8CghzZXNzaW9uMRgBIAEoCzIgLmNvbS5vcGVuanByb3h5Lm'
    'dycGMuU2Vzc2lvbkluZm9SCHNlc3Npb24xEjwKCHNlc3Npb24yGAIgASgLMiAuY29tLm9wZW5q'
    'cHJveHkuZ3JwYy5TZXNzaW9uSW5mb1IIc2Vzc2lvbjI=');

@$core.Deprecated('Use xaIsSameRMResponseDescriptor instead')
const XaIsSameRMResponse$json = {
  '1': 'XaIsSameRMResponse',
  '2': [
    {'1': 'isSame', '3': 1, '4': 1, '5': 8, '10': 'isSame'},
  ],
};

/// Descriptor for `XaIsSameRMResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaIsSameRMResponseDescriptor =
    $convert.base64Decode(
        'ChJYYUlzU2FtZVJNUmVzcG9uc2USFgoGaXNTYW1lGAEgASgIUgZpc1NhbWU=');

@$core.Deprecated('Use xaResponseDescriptor instead')
const XaResponse$json = {
  '1': 'XaResponse',
  '2': [
    {
      '1': 'session',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.com.openjproxy.grpc.SessionInfo',
      '10': 'session'
    },
    {'1': 'success', '3': 2, '4': 1, '5': 8, '10': 'success'},
    {'1': 'message', '3': 3, '4': 1, '5': 9, '10': 'message'},
  ],
};

/// Descriptor for `XaResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List xaResponseDescriptor = $convert.base64Decode(
    'CgpYYVJlc3BvbnNlEjoKB3Nlc3Npb24YASABKAsyIC5jb20ub3Blbmpwcm94eS5ncnBjLlNlc3'
    'Npb25JbmZvUgdzZXNzaW9uEhgKB3N1Y2Nlc3MYAiABKAhSB3N1Y2Nlc3MSGAoHbWVzc2FnZRgD'
    'IAEoCVIHbWVzc2FnZQ==');
