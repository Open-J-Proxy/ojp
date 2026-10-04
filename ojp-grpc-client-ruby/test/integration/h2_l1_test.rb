require_relative "../test_helper"
require "csv"
require "securerandom"

class H2L1IntegrationTest < Minitest::Test
  def test_h2_supports_l1_crud_and_lifecycle
    skip "set OJP_TEST_H2=true to run the real-server H2 L1 suite" unless integration_enabled?

    endpoint = ENV.fetch("OJP_TEST_H2_ADDR", "").strip
    flunk "OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true" if endpoint.empty?

    jdbc_url, user, password = read_connection_config
    dsn = CSV.generate_line([endpoint, jdbc_url]).strip
    dbh = DBI.connect("DBI:Ojp:#{dsn}", user, password)
    table = "ojp_ruby_l1_#{SecureRandom.hex(6)}"

    assert dbh.ping
    assert_equal 0, dbh.do("CREATE TABLE #{table} (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)")

    assert_equal 1, dbh.do("INSERT INTO #{table} (id, name) VALUES (?, ?)", 1, "before")
    assert_equal [1, "before"], dbh.execute("SELECT id, name FROM #{table} WHERE id = ?", 1).fetch
    assert_equal 1, dbh.do("UPDATE #{table} SET name = ? WHERE id = ?", "after", 1)
    assert_equal [1, "after"], dbh.execute("SELECT id, name FROM #{table} WHERE id = ?", 1).fetch
    assert_nil dbh.execute("SELECT id, name FROM #{table} WHERE id = ?", 2).fetch
    assert_equal 1, dbh.do("DELETE FROM #{table} WHERE id = ?", 1)

    assert_raises(DBI::DatabaseError) { dbh.do("INSERT INTO #{table} (id, name) VALUES (1, 'duplicate')") }
    assert_raises(DBI::DatabaseError) { dbh.execute("THIS IS NOT VALID SQL") }

    assert_equal 0, dbh.do("DROP TABLE IF EXISTS #{table}")
    assert dbh.disconnect
    assert_raises(DBI::Error) { dbh.ping }
  ensure
    dbh&.disconnect if dbh&.connected?
  end

  def test_connection_fixture_has_one_h2_configuration
    assert_equal ["jdbc:h2:mem:ojp_ruby_h2_l1;DB_CLOSE_DELAY=-1", "sa", ""], read_connection_config
  end

  private

  def read_connection_config
    records = CSV.read(File.join(__dir__, "../testdata/h2_l1_connection.csv"))
    raise "expected exactly one three-field H2 connection record" unless records.length == 1 && records.first.length == 3
    raise "H2 JDBC URL is required in the connection CSV" if records.first.first.to_s.strip.empty?

    records.first.map(&:strip)
  end

  def integration_enabled?
    case ENV.fetch("OJP_TEST_H2", "").strip.downcase
    when "", "false", "0", "no"
      false
    when "true", "1", "yes"
      true
    else
      raise ArgumentError, "OJP_TEST_H2 must be true or false"
    end
  end
end
