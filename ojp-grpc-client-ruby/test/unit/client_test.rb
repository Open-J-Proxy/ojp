require_relative "../test_helper"

class ClientTest < Minitest::Test
  def test_encodes_l1_scalar_parameters
    client = Ojp::Client.allocate
    parameters = client.send(:encode_parameters, [nil, true, 12, 2**40, 1.5, "value"])

    assert_equal 6, parameters.length
    assert_equal 1, parameters[0].index
    assert_equal :PT_NULL, parameters[0].type
    assert_equal :is_null, parameters[0].values.first.value
    assert_equal :bool_value, parameters[1].values.first.value
    assert_equal :int_value, parameters[2].values.first.value
    assert_equal :long_value, parameters[3].values.first.value
    assert_equal :double_value, parameters[4].values.first.value
    assert_equal "value", parameters[5].values.first.string_value
  end

  def test_decodes_basic_result_values
    client = Ojp::Client.allocate

    assert_equal 12, client.send(:decode_value, Com::Openjproxy::Grpc::ParameterValue.new(int_value: 12))
    assert_equal "value", client.send(
      :decode_value,
      Com::Openjproxy::Grpc::ParameterValue.new(string_value: "value")
    )
    assert_nil client.send(:decode_value, Com::Openjproxy::Grpc::ParameterValue.new(is_null: true))
  end

  def test_rejects_incomplete_dbi_data_source
    driver = DBI::DBD::Ojp::Driver.new

    assert_raises(DBI::InterfaceError) { driver.connect("", "user", "password", {}) }
  end

  def test_rejects_multiple_dbi_data_source_records
    driver = DBI::DBD::Ojp::Driver.new

    assert_raises(DBI::InterfaceError) do
      driver.connect("localhost:1059,jdbc:h2:mem:test\nlocalhost:1059,jdbc:h2:mem:other", "sa", "", {})
    end
  end

  def test_dbi_native_type_preserves_decoded_values
    row = DBI::Row.new(
      ["id", "name"],
      [DBI::DBD::Ojp::NativeValue, DBI::DBD::Ojp::NativeValue],
      [12, "value"]
    )

    assert_equal [12, "value"], row.to_a
  end
end
