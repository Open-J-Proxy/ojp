require "csv"
require "ojp/client"

module DBI
  module DBD
    module Ojp
      DBI::TypeUtil.register_conversion("Ojp") { |value| [value, false] }

      def self.driver_name
        "Ojp"
      end

      class NativeValue
        def self.parse(value)
          value
        end
      end

      class Driver < DBI::BaseDriver
        def initialize
          super(DBI::VERSION)
          @connections = []
          @mutex = Mutex.new
        end

        def connect(dbname, user, auth, attributes)
          records = CSV.parse(dbname.to_s)
          fields = records.first if records.length == 1
          unless fields && fields.length == 2 && fields.all? { |value| !value.to_s.strip.empty? }
            raise DBI::InterfaceError, "OJP DSN must contain endpoint and database URL as CSV fields"
          end

          endpoint = fields[0].strip
          url = fields[1].strip
          client = ::Ojp::Client.new(
            endpoint: endpoint,
            url: url,
            user: user,
            password: auth
          )
          database = Database.new(client, attributes, self)
          @mutex.synchronize { @connections << database }
          database
        rescue CSV::MalformedCSVError => error
          raise DBI::InterfaceError, "invalid OJP DSN CSV: #{error.message}"
        end

        def disconnect_all
          connections = @mutex.synchronize do
            active = @connections
            @connections = []
            active
          end
          errors = connections.filter_map do |connection|
            connection.disconnect
            nil
          rescue StandardError => error
            error
          end
          raise errors.first unless errors.empty?

          true
        end

        def remove_connection(database)
          @mutex.synchronize { @connections.delete(database) }
        end
      end

      class Database < DBI::BaseDatabase
        def initialize(client, attributes, driver)
          super(nil, {})
          @client = client
          @driver = driver
          @attr = attributes || {}
        end

        def disconnect
          @client.close
        ensure
          @driver.remove_connection(self)
        end

        def ping
          @client.ping
        end

        def prepare(sql)
          Statement.new(@client, sql)
        end

        def columns(_table)
          []
        end
      end

      class Statement < DBI::BaseStatement
        def initialize(client, sql)
          super()
          @client = client
          @sql = sql
          @bindings = {}
          @columns = []
          @rows = []
          @position = 0
          @affected_rows = 0
          @finished = false
        end

        def bind_param(index, value, _attributes)
          raise DBI::InterfaceError, "parameter index must be positive" unless index.is_a?(Integer) && index.positive?

          @bindings[index] = value
        end

        def execute
          raise DBI::InterfaceError, "statement is finished" if @finished

          @columns, @rows, @affected_rows = @client.execute(
            @sql,
            (1..@bindings.keys.max.to_i).map { |index| @bindings[index] }
          )
          @position = 0
          @bindings.clear
          nil
        end

        def fetch
          return nil if @position >= @rows.length

          row = @rows[@position]
          @position += 1
          row.dup
        end

        def finish
          @rows.clear
          @bindings.clear
          @finished = true
          nil
        end

        def cancel
          @rows.clear
          @position = 0
        end

        def rows
          @affected_rows
        end

        def column_info
          @columns.map do |name|
            DBI::ColumnInfo.new(
              name: name,
              dbi_type: NativeValue,
              precision: nil,
              scale: nil,
              nullable: true
            )
          end
        end
      end
    end
  end
end
