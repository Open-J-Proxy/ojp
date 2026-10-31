// This is a generated file - do not edit.
//
// Generated from StatementService.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports

import 'dart:async' as $async;
import 'dart:core' as $core;

import 'package:grpc/service_api.dart' as $grpc;
import 'package:protobuf/protobuf.dart' as $pb;

import 'StatementService.pb.dart' as $0;

export 'StatementService.pb.dart';

@$pb.GrpcServiceName('com.openjproxy.grpc.StatementService')
class StatementServiceClient extends $grpc.Client {
  /// The hostname for this service.
  static const $core.String defaultHost = '';

  /// OAuth scopes needed for the client.
  static const $core.List<$core.String> oauthScopes = [
    '',
  ];

  StatementServiceClient(super.channel, {super.options, super.interceptors});

  $grpc.ResponseFuture<$0.SessionInfo> connect(
    $0.ConnectionDetails request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$connect, request, options: options);
  }

  $grpc.ResponseFuture<$0.OpResult> executeUpdate(
    $0.StatementRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$executeUpdate, request, options: options);
  }

  $grpc.ResponseStream<$0.OpResult> executeQuery(
    $0.StatementRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createStreamingCall(
        _$executeQuery, $async.Stream.fromIterable([request]),
        options: options);
  }

  $grpc.ResponseFuture<$0.OpResult> fetchNextRows(
    $0.ResultSetFetchRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$fetchNextRows, request, options: options);
  }

  $grpc.ResponseStream<$0.LobReference> createLob(
    $async.Stream<$0.LobDataBlock> request, {
    $grpc.CallOptions? options,
  }) {
    return $createStreamingCall(_$createLob, request, options: options);
  }

  $grpc.ResponseStream<$0.LobDataBlock> readLob(
    $0.ReadLobRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createStreamingCall(
        _$readLob, $async.Stream.fromIterable([request]),
        options: options);
  }

  $grpc.ResponseFuture<$0.SessionTerminationStatus> terminateSession(
    $0.SessionInfo request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$terminateSession, request, options: options);
  }

  $grpc.ResponseFuture<$0.SessionInfo> startTransaction(
    $0.SessionInfo request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$startTransaction, request, options: options);
  }

  $grpc.ResponseFuture<$0.SessionInfo> commitTransaction(
    $0.SessionInfo request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$commitTransaction, request, options: options);
  }

  $grpc.ResponseFuture<$0.SessionInfo> rollbackTransaction(
    $0.SessionInfo request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$rollbackTransaction, request, options: options);
  }

  $grpc.ResponseFuture<$0.CallResourceResponse> callResource(
    $0.CallResourceRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$callResource, request, options: options);
  }

  /// XA Transaction Operations
  $grpc.ResponseFuture<$0.XaResponse> xaStart(
    $0.XaStartRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaStart, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaResponse> xaEnd(
    $0.XaEndRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaEnd, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaPrepareResponse> xaPrepare(
    $0.XaPrepareRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaPrepare, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaResponse> xaCommit(
    $0.XaCommitRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaCommit, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaResponse> xaRollback(
    $0.XaRollbackRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaRollback, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaRecoverResponse> xaRecover(
    $0.XaRecoverRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaRecover, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaResponse> xaForget(
    $0.XaForgetRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaForget, request, options: options);
  }

  $grpc.ResponseFuture<$0.XaSetTransactionTimeoutResponse>
      xaSetTransactionTimeout(
    $0.XaSetTransactionTimeoutRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaSetTransactionTimeout, request,
        options: options);
  }

  $grpc.ResponseFuture<$0.XaGetTransactionTimeoutResponse>
      xaGetTransactionTimeout(
    $0.XaGetTransactionTimeoutRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaGetTransactionTimeout, request,
        options: options);
  }

  $grpc.ResponseFuture<$0.XaIsSameRMResponse> xaIsSameRM(
    $0.XaIsSameRMRequest request, {
    $grpc.CallOptions? options,
  }) {
    return $createUnaryCall(_$xaIsSameRM, request, options: options);
  }

  // method descriptors

  static final _$connect =
      $grpc.ClientMethod<$0.ConnectionDetails, $0.SessionInfo>(
          '/com.openjproxy.grpc.StatementService/connect',
          ($0.ConnectionDetails value) => value.writeToBuffer(),
          $0.SessionInfo.fromBuffer);
  static final _$executeUpdate =
      $grpc.ClientMethod<$0.StatementRequest, $0.OpResult>(
          '/com.openjproxy.grpc.StatementService/executeUpdate',
          ($0.StatementRequest value) => value.writeToBuffer(),
          $0.OpResult.fromBuffer);
  static final _$executeQuery =
      $grpc.ClientMethod<$0.StatementRequest, $0.OpResult>(
          '/com.openjproxy.grpc.StatementService/executeQuery',
          ($0.StatementRequest value) => value.writeToBuffer(),
          $0.OpResult.fromBuffer);
  static final _$fetchNextRows =
      $grpc.ClientMethod<$0.ResultSetFetchRequest, $0.OpResult>(
          '/com.openjproxy.grpc.StatementService/fetchNextRows',
          ($0.ResultSetFetchRequest value) => value.writeToBuffer(),
          $0.OpResult.fromBuffer);
  static final _$createLob =
      $grpc.ClientMethod<$0.LobDataBlock, $0.LobReference>(
          '/com.openjproxy.grpc.StatementService/createLob',
          ($0.LobDataBlock value) => value.writeToBuffer(),
          $0.LobReference.fromBuffer);
  static final _$readLob =
      $grpc.ClientMethod<$0.ReadLobRequest, $0.LobDataBlock>(
          '/com.openjproxy.grpc.StatementService/readLob',
          ($0.ReadLobRequest value) => value.writeToBuffer(),
          $0.LobDataBlock.fromBuffer);
  static final _$terminateSession =
      $grpc.ClientMethod<$0.SessionInfo, $0.SessionTerminationStatus>(
          '/com.openjproxy.grpc.StatementService/terminateSession',
          ($0.SessionInfo value) => value.writeToBuffer(),
          $0.SessionTerminationStatus.fromBuffer);
  static final _$startTransaction =
      $grpc.ClientMethod<$0.SessionInfo, $0.SessionInfo>(
          '/com.openjproxy.grpc.StatementService/startTransaction',
          ($0.SessionInfo value) => value.writeToBuffer(),
          $0.SessionInfo.fromBuffer);
  static final _$commitTransaction =
      $grpc.ClientMethod<$0.SessionInfo, $0.SessionInfo>(
          '/com.openjproxy.grpc.StatementService/commitTransaction',
          ($0.SessionInfo value) => value.writeToBuffer(),
          $0.SessionInfo.fromBuffer);
  static final _$rollbackTransaction =
      $grpc.ClientMethod<$0.SessionInfo, $0.SessionInfo>(
          '/com.openjproxy.grpc.StatementService/rollbackTransaction',
          ($0.SessionInfo value) => value.writeToBuffer(),
          $0.SessionInfo.fromBuffer);
  static final _$callResource =
      $grpc.ClientMethod<$0.CallResourceRequest, $0.CallResourceResponse>(
          '/com.openjproxy.grpc.StatementService/callResource',
          ($0.CallResourceRequest value) => value.writeToBuffer(),
          $0.CallResourceResponse.fromBuffer);
  static final _$xaStart = $grpc.ClientMethod<$0.XaStartRequest, $0.XaResponse>(
      '/com.openjproxy.grpc.StatementService/xaStart',
      ($0.XaStartRequest value) => value.writeToBuffer(),
      $0.XaResponse.fromBuffer);
  static final _$xaEnd = $grpc.ClientMethod<$0.XaEndRequest, $0.XaResponse>(
      '/com.openjproxy.grpc.StatementService/xaEnd',
      ($0.XaEndRequest value) => value.writeToBuffer(),
      $0.XaResponse.fromBuffer);
  static final _$xaPrepare =
      $grpc.ClientMethod<$0.XaPrepareRequest, $0.XaPrepareResponse>(
          '/com.openjproxy.grpc.StatementService/xaPrepare',
          ($0.XaPrepareRequest value) => value.writeToBuffer(),
          $0.XaPrepareResponse.fromBuffer);
  static final _$xaCommit =
      $grpc.ClientMethod<$0.XaCommitRequest, $0.XaResponse>(
          '/com.openjproxy.grpc.StatementService/xaCommit',
          ($0.XaCommitRequest value) => value.writeToBuffer(),
          $0.XaResponse.fromBuffer);
  static final _$xaRollback =
      $grpc.ClientMethod<$0.XaRollbackRequest, $0.XaResponse>(
          '/com.openjproxy.grpc.StatementService/xaRollback',
          ($0.XaRollbackRequest value) => value.writeToBuffer(),
          $0.XaResponse.fromBuffer);
  static final _$xaRecover =
      $grpc.ClientMethod<$0.XaRecoverRequest, $0.XaRecoverResponse>(
          '/com.openjproxy.grpc.StatementService/xaRecover',
          ($0.XaRecoverRequest value) => value.writeToBuffer(),
          $0.XaRecoverResponse.fromBuffer);
  static final _$xaForget =
      $grpc.ClientMethod<$0.XaForgetRequest, $0.XaResponse>(
          '/com.openjproxy.grpc.StatementService/xaForget',
          ($0.XaForgetRequest value) => value.writeToBuffer(),
          $0.XaResponse.fromBuffer);
  static final _$xaSetTransactionTimeout = $grpc.ClientMethod<
          $0.XaSetTransactionTimeoutRequest,
          $0.XaSetTransactionTimeoutResponse>(
      '/com.openjproxy.grpc.StatementService/xaSetTransactionTimeout',
      ($0.XaSetTransactionTimeoutRequest value) => value.writeToBuffer(),
      $0.XaSetTransactionTimeoutResponse.fromBuffer);
  static final _$xaGetTransactionTimeout = $grpc.ClientMethod<
          $0.XaGetTransactionTimeoutRequest,
          $0.XaGetTransactionTimeoutResponse>(
      '/com.openjproxy.grpc.StatementService/xaGetTransactionTimeout',
      ($0.XaGetTransactionTimeoutRequest value) => value.writeToBuffer(),
      $0.XaGetTransactionTimeoutResponse.fromBuffer);
  static final _$xaIsSameRM =
      $grpc.ClientMethod<$0.XaIsSameRMRequest, $0.XaIsSameRMResponse>(
          '/com.openjproxy.grpc.StatementService/xaIsSameRM',
          ($0.XaIsSameRMRequest value) => value.writeToBuffer(),
          $0.XaIsSameRMResponse.fromBuffer);
}

@$pb.GrpcServiceName('com.openjproxy.grpc.StatementService')
abstract class StatementServiceBase extends $grpc.Service {
  $core.String get $name => 'com.openjproxy.grpc.StatementService';

  StatementServiceBase() {
    $addMethod($grpc.ServiceMethod<$0.ConnectionDetails, $0.SessionInfo>(
        'connect',
        connect_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.ConnectionDetails.fromBuffer(value),
        ($0.SessionInfo value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.StatementRequest, $0.OpResult>(
        'executeUpdate',
        executeUpdate_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.StatementRequest.fromBuffer(value),
        ($0.OpResult value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.StatementRequest, $0.OpResult>(
        'executeQuery',
        executeQuery_Pre,
        false,
        true,
        ($core.List<$core.int> value) => $0.StatementRequest.fromBuffer(value),
        ($0.OpResult value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.ResultSetFetchRequest, $0.OpResult>(
        'fetchNextRows',
        fetchNextRows_Pre,
        false,
        false,
        ($core.List<$core.int> value) =>
            $0.ResultSetFetchRequest.fromBuffer(value),
        ($0.OpResult value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.LobDataBlock, $0.LobReference>(
        'createLob',
        createLob,
        true,
        true,
        ($core.List<$core.int> value) => $0.LobDataBlock.fromBuffer(value),
        ($0.LobReference value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.ReadLobRequest, $0.LobDataBlock>(
        'readLob',
        readLob_Pre,
        false,
        true,
        ($core.List<$core.int> value) => $0.ReadLobRequest.fromBuffer(value),
        ($0.LobDataBlock value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.SessionInfo, $0.SessionTerminationStatus>(
        'terminateSession',
        terminateSession_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.SessionInfo.fromBuffer(value),
        ($0.SessionTerminationStatus value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.SessionInfo, $0.SessionInfo>(
        'startTransaction',
        startTransaction_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.SessionInfo.fromBuffer(value),
        ($0.SessionInfo value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.SessionInfo, $0.SessionInfo>(
        'commitTransaction',
        commitTransaction_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.SessionInfo.fromBuffer(value),
        ($0.SessionInfo value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.SessionInfo, $0.SessionInfo>(
        'rollbackTransaction',
        rollbackTransaction_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.SessionInfo.fromBuffer(value),
        ($0.SessionInfo value) => value.writeToBuffer()));
    $addMethod(
        $grpc.ServiceMethod<$0.CallResourceRequest, $0.CallResourceResponse>(
            'callResource',
            callResource_Pre,
            false,
            false,
            ($core.List<$core.int> value) =>
                $0.CallResourceRequest.fromBuffer(value),
            ($0.CallResourceResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaStartRequest, $0.XaResponse>(
        'xaStart',
        xaStart_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaStartRequest.fromBuffer(value),
        ($0.XaResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaEndRequest, $0.XaResponse>(
        'xaEnd',
        xaEnd_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaEndRequest.fromBuffer(value),
        ($0.XaResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaPrepareRequest, $0.XaPrepareResponse>(
        'xaPrepare',
        xaPrepare_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaPrepareRequest.fromBuffer(value),
        ($0.XaPrepareResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaCommitRequest, $0.XaResponse>(
        'xaCommit',
        xaCommit_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaCommitRequest.fromBuffer(value),
        ($0.XaResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaRollbackRequest, $0.XaResponse>(
        'xaRollback',
        xaRollback_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaRollbackRequest.fromBuffer(value),
        ($0.XaResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaRecoverRequest, $0.XaRecoverResponse>(
        'xaRecover',
        xaRecover_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaRecoverRequest.fromBuffer(value),
        ($0.XaRecoverResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaForgetRequest, $0.XaResponse>(
        'xaForget',
        xaForget_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaForgetRequest.fromBuffer(value),
        ($0.XaResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaSetTransactionTimeoutRequest,
            $0.XaSetTransactionTimeoutResponse>(
        'xaSetTransactionTimeout',
        xaSetTransactionTimeout_Pre,
        false,
        false,
        ($core.List<$core.int> value) =>
            $0.XaSetTransactionTimeoutRequest.fromBuffer(value),
        ($0.XaSetTransactionTimeoutResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaGetTransactionTimeoutRequest,
            $0.XaGetTransactionTimeoutResponse>(
        'xaGetTransactionTimeout',
        xaGetTransactionTimeout_Pre,
        false,
        false,
        ($core.List<$core.int> value) =>
            $0.XaGetTransactionTimeoutRequest.fromBuffer(value),
        ($0.XaGetTransactionTimeoutResponse value) => value.writeToBuffer()));
    $addMethod($grpc.ServiceMethod<$0.XaIsSameRMRequest, $0.XaIsSameRMResponse>(
        'xaIsSameRM',
        xaIsSameRM_Pre,
        false,
        false,
        ($core.List<$core.int> value) => $0.XaIsSameRMRequest.fromBuffer(value),
        ($0.XaIsSameRMResponse value) => value.writeToBuffer()));
  }

  $async.Future<$0.SessionInfo> connect_Pre($grpc.ServiceCall $call,
      $async.Future<$0.ConnectionDetails> $request) async {
    return connect($call, await $request);
  }

  $async.Future<$0.SessionInfo> connect(
      $grpc.ServiceCall call, $0.ConnectionDetails request);

  $async.Future<$0.OpResult> executeUpdate_Pre($grpc.ServiceCall $call,
      $async.Future<$0.StatementRequest> $request) async {
    return executeUpdate($call, await $request);
  }

  $async.Future<$0.OpResult> executeUpdate(
      $grpc.ServiceCall call, $0.StatementRequest request);

  $async.Stream<$0.OpResult> executeQuery_Pre($grpc.ServiceCall $call,
      $async.Future<$0.StatementRequest> $request) async* {
    yield* executeQuery($call, await $request);
  }

  $async.Stream<$0.OpResult> executeQuery(
      $grpc.ServiceCall call, $0.StatementRequest request);

  $async.Future<$0.OpResult> fetchNextRows_Pre($grpc.ServiceCall $call,
      $async.Future<$0.ResultSetFetchRequest> $request) async {
    return fetchNextRows($call, await $request);
  }

  $async.Future<$0.OpResult> fetchNextRows(
      $grpc.ServiceCall call, $0.ResultSetFetchRequest request);

  $async.Stream<$0.LobReference> createLob(
      $grpc.ServiceCall call, $async.Stream<$0.LobDataBlock> request);

  $async.Stream<$0.LobDataBlock> readLob_Pre($grpc.ServiceCall $call,
      $async.Future<$0.ReadLobRequest> $request) async* {
    yield* readLob($call, await $request);
  }

  $async.Stream<$0.LobDataBlock> readLob(
      $grpc.ServiceCall call, $0.ReadLobRequest request);

  $async.Future<$0.SessionTerminationStatus> terminateSession_Pre(
      $grpc.ServiceCall $call, $async.Future<$0.SessionInfo> $request) async {
    return terminateSession($call, await $request);
  }

  $async.Future<$0.SessionTerminationStatus> terminateSession(
      $grpc.ServiceCall call, $0.SessionInfo request);

  $async.Future<$0.SessionInfo> startTransaction_Pre(
      $grpc.ServiceCall $call, $async.Future<$0.SessionInfo> $request) async {
    return startTransaction($call, await $request);
  }

  $async.Future<$0.SessionInfo> startTransaction(
      $grpc.ServiceCall call, $0.SessionInfo request);

  $async.Future<$0.SessionInfo> commitTransaction_Pre(
      $grpc.ServiceCall $call, $async.Future<$0.SessionInfo> $request) async {
    return commitTransaction($call, await $request);
  }

  $async.Future<$0.SessionInfo> commitTransaction(
      $grpc.ServiceCall call, $0.SessionInfo request);

  $async.Future<$0.SessionInfo> rollbackTransaction_Pre(
      $grpc.ServiceCall $call, $async.Future<$0.SessionInfo> $request) async {
    return rollbackTransaction($call, await $request);
  }

  $async.Future<$0.SessionInfo> rollbackTransaction(
      $grpc.ServiceCall call, $0.SessionInfo request);

  $async.Future<$0.CallResourceResponse> callResource_Pre(
      $grpc.ServiceCall $call,
      $async.Future<$0.CallResourceRequest> $request) async {
    return callResource($call, await $request);
  }

  $async.Future<$0.CallResourceResponse> callResource(
      $grpc.ServiceCall call, $0.CallResourceRequest request);

  $async.Future<$0.XaResponse> xaStart_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaStartRequest> $request) async {
    return xaStart($call, await $request);
  }

  $async.Future<$0.XaResponse> xaStart(
      $grpc.ServiceCall call, $0.XaStartRequest request);

  $async.Future<$0.XaResponse> xaEnd_Pre(
      $grpc.ServiceCall $call, $async.Future<$0.XaEndRequest> $request) async {
    return xaEnd($call, await $request);
  }

  $async.Future<$0.XaResponse> xaEnd(
      $grpc.ServiceCall call, $0.XaEndRequest request);

  $async.Future<$0.XaPrepareResponse> xaPrepare_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaPrepareRequest> $request) async {
    return xaPrepare($call, await $request);
  }

  $async.Future<$0.XaPrepareResponse> xaPrepare(
      $grpc.ServiceCall call, $0.XaPrepareRequest request);

  $async.Future<$0.XaResponse> xaCommit_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaCommitRequest> $request) async {
    return xaCommit($call, await $request);
  }

  $async.Future<$0.XaResponse> xaCommit(
      $grpc.ServiceCall call, $0.XaCommitRequest request);

  $async.Future<$0.XaResponse> xaRollback_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaRollbackRequest> $request) async {
    return xaRollback($call, await $request);
  }

  $async.Future<$0.XaResponse> xaRollback(
      $grpc.ServiceCall call, $0.XaRollbackRequest request);

  $async.Future<$0.XaRecoverResponse> xaRecover_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaRecoverRequest> $request) async {
    return xaRecover($call, await $request);
  }

  $async.Future<$0.XaRecoverResponse> xaRecover(
      $grpc.ServiceCall call, $0.XaRecoverRequest request);

  $async.Future<$0.XaResponse> xaForget_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaForgetRequest> $request) async {
    return xaForget($call, await $request);
  }

  $async.Future<$0.XaResponse> xaForget(
      $grpc.ServiceCall call, $0.XaForgetRequest request);

  $async.Future<$0.XaSetTransactionTimeoutResponse> xaSetTransactionTimeout_Pre(
      $grpc.ServiceCall $call,
      $async.Future<$0.XaSetTransactionTimeoutRequest> $request) async {
    return xaSetTransactionTimeout($call, await $request);
  }

  $async.Future<$0.XaSetTransactionTimeoutResponse> xaSetTransactionTimeout(
      $grpc.ServiceCall call, $0.XaSetTransactionTimeoutRequest request);

  $async.Future<$0.XaGetTransactionTimeoutResponse> xaGetTransactionTimeout_Pre(
      $grpc.ServiceCall $call,
      $async.Future<$0.XaGetTransactionTimeoutRequest> $request) async {
    return xaGetTransactionTimeout($call, await $request);
  }

  $async.Future<$0.XaGetTransactionTimeoutResponse> xaGetTransactionTimeout(
      $grpc.ServiceCall call, $0.XaGetTransactionTimeoutRequest request);

  $async.Future<$0.XaIsSameRMResponse> xaIsSameRM_Pre($grpc.ServiceCall $call,
      $async.Future<$0.XaIsSameRMRequest> $request) async {
    return xaIsSameRM($call, await $request);
  }

  $async.Future<$0.XaIsSameRMResponse> xaIsSameRM(
      $grpc.ServiceCall call, $0.XaIsSameRMRequest request);
}
