require "securerandom"
require "date"
require "dbi"
require "grpc"
require "StatementService_pb"
require "StatementService_services_pb"

module Ojp
  class SqlError < DBI::DatabaseError
    attr_reader :sql_state, :vendor_code

    def initialize(message, sql_state = nil, vendor_code = nil, cause = nil)
      super(message, vendor_code, sql_state)
      @sql_state = sql_state
      @vendor_code = vendor_code
      set_backtrace(cause.backtrace) if cause
    end
  end

  class Client
    CLIENT_UUID = SecureRandom.uuid.freeze
    QUERY_PREFIX = /\A(?:SELECT|WITH|VALUES|TABLE|SHOW|EXPLAIN)\b/i

    attr_reader :session

    def initialize(endpoint:, url:, user:, password:)
      raise ArgumentError, "OJP endpoint is required" if endpoint.to_s.strip.empty?
      raise ArgumentError, "database URL is required" if url.to_s.strip.empty?

      @endpoint = endpoint
      @url = url
      @user = user || ""
      @password = password || ""
      @channel = GRPC::Core::Channel.new(endpoint, {}, :this_channel_is_insecure)
      @stub = Com::Openjproxy::Grpc::StatementService::Stub.new(
        endpoint,
        :this_channel_is_insecure,
        channel_override: @channel
      )
      @mutex = Mutex.new
      @closed = false
      @session = @stub.connect(Com::Openjproxy::Grpc::ConnectionDetails.new(
        url: @url,
        user: @user,
        password: @password,
        clientUUID: CLIENT_UUID,
        serverEndpoints: [@endpoint],
        clusterHealth: "",
        isXA: false
      ))
      raise IOError, "OJP server returned an empty session" unless @session
    rescue GRPC::BadStatus => error
      @channel&.close
      raise map_rpc_error(error)
    end

    def ping
      execute("SELECT 1", []).first
      true
    end

    def execute(sql, parameters)
      @mutex.synchronize do
        ensure_open
        if query_sql?(sql)
          execute_query(sql, parameters)
        else
          execute_update(sql, parameters)
        end
      end
    end

    def close
      @mutex.synchronize do
        return false if @closed

        begin
          response = @stub.terminate_session(@session)
          raise IOError, "OJP server did not terminate the session" unless response&.terminated
        rescue GRPC::BadStatus => error
          raise map_rpc_error(error)
        ensure
          @closed = true
          @channel.close
        end
        true
      end
    end

    private

    def ensure_open
      raise IOError, "OJP connection is closed" if @closed
    end

    def query_sql?(sql)
      sql.to_s.sub(/\A(?:(?:\s+)|(?:--[^\n]*(?:\n|\z))|(?:\/\*.*?\*\/))*/, "").match?(QUERY_PREFIX)
    end

    def execute_update(sql, parameters)
      result = @stub.execute_update(Com::Openjproxy::Grpc::StatementRequest.new(
        session: @session,
        sql: sql,
        parameters: encode_parameters(parameters)
      ))
      @session = result.session if result&.session
      unless result && result.type == :INTEGER
        raise IOError, "OJP server returned an invalid update result"
      end

      [[], [], result.int_value]
    rescue GRPC::BadStatus => error
      raise map_rpc_error(error)
    end

    def execute_query(sql, parameters)
      columns = []
      rows = []
      @stub.execute_query(Com::Openjproxy::Grpc::StatementRequest.new(
        session: @session,
        sql: sql,
        parameters: encode_parameters(parameters)
      )).each do |result|
        @session = result.session if result.session
        query_result = result.query_result
        next unless query_result

        columns = query_result.labels if columns.empty?
        query_result.rows.each do |row|
          rows << row.columns.map { |value| decode_value(value) }
        end
      end
      [columns, rows, rows.length]
    rescue GRPC::BadStatus => error
      raise map_rpc_error(error)
    end

    def encode_parameters(parameters)
      Array(parameters).each_with_index.map do |value, index|
        type, proto_value = case value
                            when nil
                              [Com::Openjproxy::Grpc::ParameterTypeProto::PT_NULL,
                               Com::Openjproxy::Grpc::ParameterValue.new(is_null: true)]
                            when true, false
                              [Com::Openjproxy::Grpc::ParameterTypeProto::PT_BOOLEAN,
                               Com::Openjproxy::Grpc::ParameterValue.new(bool_value: value)]
                            when Integer
                              type = value.between?(-(2**31), (2**31) - 1) ?
                                Com::Openjproxy::Grpc::ParameterTypeProto::PT_INT :
                                Com::Openjproxy::Grpc::ParameterTypeProto::PT_LONG
                              field = type == Com::Openjproxy::Grpc::ParameterTypeProto::PT_INT ? :int_value : :long_value
                              [type, Com::Openjproxy::Grpc::ParameterValue.new(field => value)]
                            when Float
                              [Com::Openjproxy::Grpc::ParameterTypeProto::PT_DOUBLE,
                               Com::Openjproxy::Grpc::ParameterValue.new(double_value: value)]
                            when String
                              [Com::Openjproxy::Grpc::ParameterTypeProto::PT_STRING,
                               Com::Openjproxy::Grpc::ParameterValue.new(string_value: value)]
                            when Time
                              timestamp = Google::Protobuf::Timestamp.new(seconds: value.to_i, nanos: value.nsec)
                              offset = value.utc_offset
                              timezone = format("%+03d:%02d", offset / 3600, (offset.abs % 3600) / 60)
                              zone_value = Com::Openjproxy::Grpc::TimestampWithZone.new(
                                instant: timestamp,
                                timezone: timezone
                              )
                              [Com::Openjproxy::Grpc::ParameterTypeProto::PT_TIMESTAMP,
                               Com::Openjproxy::Grpc::ParameterValue.new(timestamp_value: zone_value)]
                            else
                              raise ArgumentError, "unsupported OJP parameter type #{value.class}"
                            end
        Com::Openjproxy::Grpc::ParameterProto.new(
          index: index + 1,
          type: type,
          values: [proto_value]
        )
      end
    end

    def decode_value(value)
      case value.value
      when :is_null then nil
      when :bool_value then value.bool_value
      when :int_value then value.int_value
      when :long_value then value.long_value
      when :float_value then value.float_value
      when :double_value then value.double_value
      when :string_value then value.string_value
      when :bytes_value then value.bytes_value
      when :timestamp_value
        instant = value.timestamp_value.instant
        timestamp = Time.at(instant.seconds, instant.nanos, :nanosecond)
        timezone = value.timestamp_value.timezone
        timezone.match?(/\A(?:[+-]\d{2}:\d{2}|UTC)\z/) ? timestamp.getlocal(timezone) : timestamp.utc
      when :date_value
        Date.new(value.date_value.year, value.date_value.month, value.date_value.day)
      when :time_value
        Time.new(2000, 1, 1, value.time_value.hours, value.time_value.minutes,
                 value.time_value.seconds + value.time_value.nanos / 1_000_000_000.0, "+00:00")
      else
        nil
      end
    end

    def map_rpc_error(error)
      trailers = error.metadata || {}
      payload = trailers.find { |key, _| key.to_s.downcase.end_with?("sqlerrorresponse-bin") }&.last
      payload = payload.first if payload.is_a?(Array)
      if payload
        response = Com::Openjproxy::Grpc::SqlErrorResponse.decode(payload)
        return SqlError.new(response.reason, response.sqlState, response.vendorCode, error)
      end

      error
    rescue Google::Protobuf::ParseError
      error
    end
  end
end
