// This is a generated file - do not edit.
//
// Generated from StatementService.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports

import 'dart:core' as $core;

import 'package:protobuf/protobuf.dart' as $pb;

/// Enum representing parameter types for JDBC
class ParameterTypeProto extends $pb.ProtobufEnum {
  static const ParameterTypeProto PT_NULL =
      ParameterTypeProto._(0, _omitEnumNames ? '' : 'PT_NULL');
  static const ParameterTypeProto PT_BOOLEAN =
      ParameterTypeProto._(1, _omitEnumNames ? '' : 'PT_BOOLEAN');
  static const ParameterTypeProto PT_BYTE =
      ParameterTypeProto._(2, _omitEnumNames ? '' : 'PT_BYTE');
  static const ParameterTypeProto PT_SHORT =
      ParameterTypeProto._(3, _omitEnumNames ? '' : 'PT_SHORT');
  static const ParameterTypeProto PT_INT =
      ParameterTypeProto._(4, _omitEnumNames ? '' : 'PT_INT');
  static const ParameterTypeProto PT_LONG =
      ParameterTypeProto._(5, _omitEnumNames ? '' : 'PT_LONG');
  static const ParameterTypeProto PT_FLOAT =
      ParameterTypeProto._(6, _omitEnumNames ? '' : 'PT_FLOAT');
  static const ParameterTypeProto PT_DOUBLE =
      ParameterTypeProto._(7, _omitEnumNames ? '' : 'PT_DOUBLE');
  static const ParameterTypeProto PT_BIG_DECIMAL =
      ParameterTypeProto._(8, _omitEnumNames ? '' : 'PT_BIG_DECIMAL');
  static const ParameterTypeProto PT_STRING =
      ParameterTypeProto._(9, _omitEnumNames ? '' : 'PT_STRING');
  static const ParameterTypeProto PT_BYTES =
      ParameterTypeProto._(10, _omitEnumNames ? '' : 'PT_BYTES');
  static const ParameterTypeProto PT_DATE =
      ParameterTypeProto._(11, _omitEnumNames ? '' : 'PT_DATE');
  static const ParameterTypeProto PT_TIME =
      ParameterTypeProto._(12, _omitEnumNames ? '' : 'PT_TIME');
  static const ParameterTypeProto PT_TIMESTAMP =
      ParameterTypeProto._(13, _omitEnumNames ? '' : 'PT_TIMESTAMP');
  static const ParameterTypeProto PT_ASCII_STREAM =
      ParameterTypeProto._(14, _omitEnumNames ? '' : 'PT_ASCII_STREAM');
  static const ParameterTypeProto PT_UNICODE_STREAM =
      ParameterTypeProto._(15, _omitEnumNames ? '' : 'PT_UNICODE_STREAM');
  static const ParameterTypeProto PT_BINARY_STREAM =
      ParameterTypeProto._(16, _omitEnumNames ? '' : 'PT_BINARY_STREAM');
  static const ParameterTypeProto PT_OBJECT =
      ParameterTypeProto._(17, _omitEnumNames ? '' : 'PT_OBJECT');
  static const ParameterTypeProto PT_CHARACTER_READER =
      ParameterTypeProto._(18, _omitEnumNames ? '' : 'PT_CHARACTER_READER');
  static const ParameterTypeProto PT_REF =
      ParameterTypeProto._(19, _omitEnumNames ? '' : 'PT_REF');
  static const ParameterTypeProto PT_BLOB =
      ParameterTypeProto._(20, _omitEnumNames ? '' : 'PT_BLOB');
  static const ParameterTypeProto PT_CLOB =
      ParameterTypeProto._(21, _omitEnumNames ? '' : 'PT_CLOB');
  static const ParameterTypeProto PT_ARRAY =
      ParameterTypeProto._(22, _omitEnumNames ? '' : 'PT_ARRAY');
  static const ParameterTypeProto PT_URL =
      ParameterTypeProto._(23, _omitEnumNames ? '' : 'PT_URL');
  static const ParameterTypeProto PT_ROW_ID =
      ParameterTypeProto._(24, _omitEnumNames ? '' : 'PT_ROW_ID');
  static const ParameterTypeProto PT_N_STRING =
      ParameterTypeProto._(25, _omitEnumNames ? '' : 'PT_N_STRING');
  static const ParameterTypeProto PT_N_CHARACTER_STREAM =
      ParameterTypeProto._(26, _omitEnumNames ? '' : 'PT_N_CHARACTER_STREAM');
  static const ParameterTypeProto PT_N_CLOB =
      ParameterTypeProto._(27, _omitEnumNames ? '' : 'PT_N_CLOB');
  static const ParameterTypeProto PT_SQL_XML =
      ParameterTypeProto._(28, _omitEnumNames ? '' : 'PT_SQL_XML');

  static const $core.List<ParameterTypeProto> values = <ParameterTypeProto>[
    PT_NULL,
    PT_BOOLEAN,
    PT_BYTE,
    PT_SHORT,
    PT_INT,
    PT_LONG,
    PT_FLOAT,
    PT_DOUBLE,
    PT_BIG_DECIMAL,
    PT_STRING,
    PT_BYTES,
    PT_DATE,
    PT_TIME,
    PT_TIMESTAMP,
    PT_ASCII_STREAM,
    PT_UNICODE_STREAM,
    PT_BINARY_STREAM,
    PT_OBJECT,
    PT_CHARACTER_READER,
    PT_REF,
    PT_BLOB,
    PT_CLOB,
    PT_ARRAY,
    PT_URL,
    PT_ROW_ID,
    PT_N_STRING,
    PT_N_CHARACTER_STREAM,
    PT_N_CLOB,
    PT_SQL_XML,
  ];

  static final $core.List<ParameterTypeProto?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 28);
  static ParameterTypeProto? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const ParameterTypeProto._(super.value, super.name);
}

/// Enum to track the original Java type for TimestampWithZone and Date/Time types
class TemporalType extends $pb.ProtobufEnum {
  static const TemporalType TEMPORAL_TYPE_UNSPECIFIED =
      TemporalType._(0, _omitEnumNames ? '' : 'TEMPORAL_TYPE_UNSPECIFIED');
  static const TemporalType TEMPORAL_TYPE_TIMESTAMP =
      TemporalType._(1, _omitEnumNames ? '' : 'TEMPORAL_TYPE_TIMESTAMP');
  static const TemporalType TEMPORAL_TYPE_CALENDAR =
      TemporalType._(2, _omitEnumNames ? '' : 'TEMPORAL_TYPE_CALENDAR');
  static const TemporalType TEMPORAL_TYPE_OFFSET_DATE_TIME =
      TemporalType._(3, _omitEnumNames ? '' : 'TEMPORAL_TYPE_OFFSET_DATE_TIME');
  static const TemporalType TEMPORAL_TYPE_LOCAL_DATE_TIME =
      TemporalType._(4, _omitEnumNames ? '' : 'TEMPORAL_TYPE_LOCAL_DATE_TIME');
  static const TemporalType TEMPORAL_TYPE_INSTANT =
      TemporalType._(5, _omitEnumNames ? '' : 'TEMPORAL_TYPE_INSTANT');
  static const TemporalType TEMPORAL_TYPE_LOCAL_DATE =
      TemporalType._(6, _omitEnumNames ? '' : 'TEMPORAL_TYPE_LOCAL_DATE');
  static const TemporalType TEMPORAL_TYPE_LOCAL_TIME =
      TemporalType._(7, _omitEnumNames ? '' : 'TEMPORAL_TYPE_LOCAL_TIME');
  static const TemporalType TEMPORAL_TYPE_OFFSET_TIME =
      TemporalType._(8, _omitEnumNames ? '' : 'TEMPORAL_TYPE_OFFSET_TIME');

  static const $core.List<TemporalType> values = <TemporalType>[
    TEMPORAL_TYPE_UNSPECIFIED,
    TEMPORAL_TYPE_TIMESTAMP,
    TEMPORAL_TYPE_CALENDAR,
    TEMPORAL_TYPE_OFFSET_DATE_TIME,
    TEMPORAL_TYPE_LOCAL_DATE_TIME,
    TEMPORAL_TYPE_INSTANT,
    TEMPORAL_TYPE_LOCAL_DATE,
    TEMPORAL_TYPE_LOCAL_TIME,
    TEMPORAL_TYPE_OFFSET_TIME,
  ];

  static final $core.List<TemporalType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 8);
  static TemporalType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const TemporalType._(super.value, super.name);
}

class DbName extends $pb.ProtobufEnum {
  static const DbName H2 = DbName._(0, _omitEnumNames ? '' : 'H2');
  static const DbName MYSQL = DbName._(1, _omitEnumNames ? '' : 'MYSQL');
  static const DbName MARIADB = DbName._(2, _omitEnumNames ? '' : 'MARIADB');
  static const DbName POSTGRES = DbName._(3, _omitEnumNames ? '' : 'POSTGRES');
  static const DbName ORACLE = DbName._(4, _omitEnumNames ? '' : 'ORACLE');
  static const DbName SQL_SERVER =
      DbName._(5, _omitEnumNames ? '' : 'SQL_SERVER');
  static const DbName DB2 = DbName._(6, _omitEnumNames ? '' : 'DB2');
  static const DbName UNMAPPED = DbName._(7, _omitEnumNames ? '' : 'UNMAPPED');

  static const $core.List<DbName> values = <DbName>[
    H2,
    MYSQL,
    MARIADB,
    POSTGRES,
    ORACLE,
    SQL_SERVER,
    DB2,
    UNMAPPED,
  ];

  static final $core.List<DbName?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 7);
  static DbName? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const DbName._(super.value, super.name);
}

class SessionStatus extends $pb.ProtobufEnum {
  static const SessionStatus SESSION_ACTIVE =
      SessionStatus._(0, _omitEnumNames ? '' : 'SESSION_ACTIVE');
  static const SessionStatus SESSION_TERMINATED =
      SessionStatus._(1, _omitEnumNames ? '' : 'SESSION_TERMINATED');

  static const $core.List<SessionStatus> values = <SessionStatus>[
    SESSION_ACTIVE,
    SESSION_TERMINATED,
  ];

  static final $core.List<SessionStatus?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 1);
  static SessionStatus? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const SessionStatus._(super.value, super.name);
}

/// TRX stands for transaction
class TransactionStatus extends $pb.ProtobufEnum {
  static const TransactionStatus TRX_ACTIVE =
      TransactionStatus._(0, _omitEnumNames ? '' : 'TRX_ACTIVE');
  static const TransactionStatus TRX_COMMITED =
      TransactionStatus._(1, _omitEnumNames ? '' : 'TRX_COMMITED');
  static const TransactionStatus TRX_ROLLBACK =
      TransactionStatus._(2, _omitEnumNames ? '' : 'TRX_ROLLBACK');

  static const $core.List<TransactionStatus> values = <TransactionStatus>[
    TRX_ACTIVE,
    TRX_COMMITED,
    TRX_ROLLBACK,
  ];

  static final $core.List<TransactionStatus?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 2);
  static TransactionStatus? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const TransactionStatus._(super.value, super.name);
}

class ResultType extends $pb.ProtobufEnum {
  static const ResultType INTEGER =
      ResultType._(0, _omitEnumNames ? '' : 'INTEGER');
  static const ResultType RESULT_SET_DATA =
      ResultType._(1, _omitEnumNames ? '' : 'RESULT_SET_DATA');
  static const ResultType UUID_STRING =
      ResultType._(2, _omitEnumNames ? '' : 'UUID_STRING');

  static const $core.List<ResultType> values = <ResultType>[
    INTEGER,
    RESULT_SET_DATA,
    UUID_STRING,
  ];

  static final $core.List<ResultType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 2);
  static ResultType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const ResultType._(super.value, super.name);
}

class SqlErrorType extends $pb.ProtobufEnum {
  static const SqlErrorType SQL_EXCEPTION =
      SqlErrorType._(0, _omitEnumNames ? '' : 'SQL_EXCEPTION');
  static const SqlErrorType SQL_DATA_EXCEPTION =
      SqlErrorType._(1, _omitEnumNames ? '' : 'SQL_DATA_EXCEPTION');
  static const SqlErrorType SQL_TRANSIENT_CONNECTION_EXCEPTION = SqlErrorType._(
      2, _omitEnumNames ? '' : 'SQL_TRANSIENT_CONNECTION_EXCEPTION');

  static const $core.List<SqlErrorType> values = <SqlErrorType>[
    SQL_EXCEPTION,
    SQL_DATA_EXCEPTION,
    SQL_TRANSIENT_CONNECTION_EXCEPTION,
  ];

  static final $core.List<SqlErrorType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 2);
  static SqlErrorType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const SqlErrorType._(super.value, super.name);
}

/// LT stands for Lob Type
class LobType extends $pb.ProtobufEnum {
  static const LobType LT_BLOB = LobType._(0, _omitEnumNames ? '' : 'LT_BLOB');
  static const LobType LT_CLOB = LobType._(1, _omitEnumNames ? '' : 'LT_CLOB');
  static const LobType LT_BINARY_STREAM =
      LobType._(2, _omitEnumNames ? '' : 'LT_BINARY_STREAM');
  static const LobType LT_ASCII_STREAM =
      LobType._(3, _omitEnumNames ? '' : 'LT_ASCII_STREAM');
  static const LobType LT_UNICODE_STREAM =
      LobType._(4, _omitEnumNames ? '' : 'LT_UNICODE_STREAM');
  static const LobType LT_CHARACTER_STREAM =
      LobType._(5, _omitEnumNames ? '' : 'LT_CHARACTER_STREAM');

  static const $core.List<LobType> values = <LobType>[
    LT_BLOB,
    LT_CLOB,
    LT_BINARY_STREAM,
    LT_ASCII_STREAM,
    LT_UNICODE_STREAM,
    LT_CHARACTER_STREAM,
  ];

  static final $core.List<LobType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 5);
  static LobType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const LobType._(super.value, super.name);
}

class ResourceType extends $pb.ProtobufEnum {
  static const ResourceType RES_RESULT_SET =
      ResourceType._(0, _omitEnumNames ? '' : 'RES_RESULT_SET');
  static const ResourceType RES_STATEMENT =
      ResourceType._(1, _omitEnumNames ? '' : 'RES_STATEMENT');
  static const ResourceType RES_PREPARED_STATEMENT =
      ResourceType._(2, _omitEnumNames ? '' : 'RES_PREPARED_STATEMENT');
  static const ResourceType RES_CALLABLE_STATEMENT =
      ResourceType._(3, _omitEnumNames ? '' : 'RES_CALLABLE_STATEMENT');
  static const ResourceType RES_LOB =
      ResourceType._(4, _omitEnumNames ? '' : 'RES_LOB');
  static const ResourceType RES_CONNECTION =
      ResourceType._(5, _omitEnumNames ? '' : 'RES_CONNECTION');
  static const ResourceType RES_SAVEPOINT =
      ResourceType._(6, _omitEnumNames ? '' : 'RES_SAVEPOINT');

  static const $core.List<ResourceType> values = <ResourceType>[
    RES_RESULT_SET,
    RES_STATEMENT,
    RES_PREPARED_STATEMENT,
    RES_CALLABLE_STATEMENT,
    RES_LOB,
    RES_CONNECTION,
    RES_SAVEPOINT,
  ];

  static final $core.List<ResourceType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 6);
  static ResourceType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const ResourceType._(super.value, super.name);
}

class CallType extends $pb.ProtobufEnum {
  static const CallType CALL_SET =
      CallType._(0, _omitEnumNames ? '' : 'CALL_SET');
  static const CallType CALL_GET =
      CallType._(1, _omitEnumNames ? '' : 'CALL_GET');
  static const CallType CALL_IS =
      CallType._(2, _omitEnumNames ? '' : 'CALL_IS');
  static const CallType CALL_ALL =
      CallType._(3, _omitEnumNames ? '' : 'CALL_ALL');
  static const CallType CALL_NULLS =
      CallType._(4, _omitEnumNames ? '' : 'CALL_NULLS');
  static const CallType CALL_USES =
      CallType._(5, _omitEnumNames ? '' : 'CALL_USES');
  static const CallType CALL_SUPPORTS =
      CallType._(6, _omitEnumNames ? '' : 'CALL_SUPPORTS');
  static const CallType CALL_STORES =
      CallType._(7, _omitEnumNames ? '' : 'CALL_STORES');
  static const CallType CALL_NULL =
      CallType._(8, _omitEnumNames ? '' : 'CALL_NULL');
  static const CallType CALL_DOES =
      CallType._(9, _omitEnumNames ? '' : 'CALL_DOES');
  static const CallType CALL_DATA =
      CallType._(10, _omitEnumNames ? '' : 'CALL_DATA');
  static const CallType CALL_NEXT =
      CallType._(11, _omitEnumNames ? '' : 'CALL_NEXT');
  static const CallType CALL_CLOSE =
      CallType._(12, _omitEnumNames ? '' : 'CALL_CLOSE');
  static const CallType CALL_WAS =
      CallType._(13, _omitEnumNames ? '' : 'CALL_WAS');
  static const CallType CALL_CLEAR =
      CallType._(14, _omitEnumNames ? '' : 'CALL_CLEAR');
  static const CallType CALL_FIND =
      CallType._(15, _omitEnumNames ? '' : 'CALL_FIND');
  static const CallType CALL_BEFORE =
      CallType._(16, _omitEnumNames ? '' : 'CALL_BEFORE');
  static const CallType CALL_AFTER =
      CallType._(17, _omitEnumNames ? '' : 'CALL_AFTER');
  static const CallType CALL_FIRST =
      CallType._(18, _omitEnumNames ? '' : 'CALL_FIRST');
  static const CallType CALL_LAST =
      CallType._(19, _omitEnumNames ? '' : 'CALL_LAST');
  static const CallType CALL_ABSOLUTE =
      CallType._(20, _omitEnumNames ? '' : 'CALL_ABSOLUTE');
  static const CallType CALL_RELATIVE =
      CallType._(21, _omitEnumNames ? '' : 'CALL_RELATIVE');
  static const CallType CALL_PREVIOUS =
      CallType._(22, _omitEnumNames ? '' : 'CALL_PREVIOUS');
  static const CallType CALL_ROW =
      CallType._(23, _omitEnumNames ? '' : 'CALL_ROW');
  static const CallType CALL_UPDATE =
      CallType._(24, _omitEnumNames ? '' : 'CALL_UPDATE');
  static const CallType CALL_INSERT =
      CallType._(25, _omitEnumNames ? '' : 'CALL_INSERT');
  static const CallType CALL_DELETE =
      CallType._(26, _omitEnumNames ? '' : 'CALL_DELETE');
  static const CallType CALL_REFRESH =
      CallType._(27, _omitEnumNames ? '' : 'CALL_REFRESH');
  static const CallType CALL_CANCEL =
      CallType._(28, _omitEnumNames ? '' : 'CALL_CANCEL');
  static const CallType CALL_MOVE =
      CallType._(29, _omitEnumNames ? '' : 'CALL_MOVE');
  static const CallType CALL_OWN =
      CallType._(30, _omitEnumNames ? '' : 'CALL_OWN');
  static const CallType CALL_OTHERS =
      CallType._(31, _omitEnumNames ? '' : 'CALL_OTHERS');
  static const CallType CALL_UPDATES =
      CallType._(32, _omitEnumNames ? '' : 'CALL_UPDATES');
  static const CallType CALL_DELETES =
      CallType._(33, _omitEnumNames ? '' : 'CALL_DELETES');
  static const CallType CALL_INSERTS =
      CallType._(34, _omitEnumNames ? '' : 'CALL_INSERTS');
  static const CallType CALL_LOCATORS =
      CallType._(35, _omitEnumNames ? '' : 'CALL_LOCATORS');
  static const CallType CALL_AUTO =
      CallType._(36, _omitEnumNames ? '' : 'CALL_AUTO');
  static const CallType CALL_GENERATED =
      CallType._(37, _omitEnumNames ? '' : 'CALL_GENERATED');
  static const CallType CALL_RELEASE =
      CallType._(38, _omitEnumNames ? '' : 'CALL_RELEASE');
  static const CallType CALL_NATIVE =
      CallType._(39, _omitEnumNames ? '' : 'CALL_NATIVE');
  static const CallType CALL_PREPARE =
      CallType._(40, _omitEnumNames ? '' : 'CALL_PREPARE');
  static const CallType CALL_ROLLBACK =
      CallType._(41, _omitEnumNames ? '' : 'CALL_ROLLBACK');
  static const CallType CALL_ABORT =
      CallType._(42, _omitEnumNames ? '' : 'CALL_ABORT');
  static const CallType CALL_EXECUTE =
      CallType._(43, _omitEnumNames ? '' : 'CALL_EXECUTE');
  static const CallType CALL_ADD =
      CallType._(44, _omitEnumNames ? '' : 'CALL_ADD');
  static const CallType CALL_ENQUOTE =
      CallType._(45, _omitEnumNames ? '' : 'CALL_ENQUOTE');
  static const CallType CALL_REGISTER =
      CallType._(46, _omitEnumNames ? '' : 'CALL_REGISTER');
  static const CallType CALL_LENGTH =
      CallType._(47, _omitEnumNames ? '' : 'CALL_LENGTH');

  static const $core.List<CallType> values = <CallType>[
    CALL_SET,
    CALL_GET,
    CALL_IS,
    CALL_ALL,
    CALL_NULLS,
    CALL_USES,
    CALL_SUPPORTS,
    CALL_STORES,
    CALL_NULL,
    CALL_DOES,
    CALL_DATA,
    CALL_NEXT,
    CALL_CLOSE,
    CALL_WAS,
    CALL_CLEAR,
    CALL_FIND,
    CALL_BEFORE,
    CALL_AFTER,
    CALL_FIRST,
    CALL_LAST,
    CALL_ABSOLUTE,
    CALL_RELATIVE,
    CALL_PREVIOUS,
    CALL_ROW,
    CALL_UPDATE,
    CALL_INSERT,
    CALL_DELETE,
    CALL_REFRESH,
    CALL_CANCEL,
    CALL_MOVE,
    CALL_OWN,
    CALL_OTHERS,
    CALL_UPDATES,
    CALL_DELETES,
    CALL_INSERTS,
    CALL_LOCATORS,
    CALL_AUTO,
    CALL_GENERATED,
    CALL_RELEASE,
    CALL_NATIVE,
    CALL_PREPARE,
    CALL_ROLLBACK,
    CALL_ABORT,
    CALL_EXECUTE,
    CALL_ADD,
    CALL_ENQUOTE,
    CALL_REGISTER,
    CALL_LENGTH,
  ];

  static final $core.List<CallType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 47);
  static CallType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const CallType._(super.value, super.name);
}

const $core.bool _omitEnumNames =
    $core.bool.fromEnvironment('protobuf.omit_enum_names');
