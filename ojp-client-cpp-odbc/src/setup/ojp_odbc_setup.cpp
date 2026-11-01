#include <windows.h>
#include <commctrl.h>
#include <commdlg.h>
#include <sql.h>
#include <sqlext.h>
#include <odbcinst.h>

#include <cstddef>
#include <cstring>
#include <cwchar>
#include <memory>
#include <string>
#include <system_error>
#include <thread>
#include <utility>
#include <vector>

#include "ojp_odbc_dsn.h"
#include "resource.h"

namespace dsn = ojp::odbc::dsn;

namespace {

constexpr UINT WM_TEST_FINISHED = WM_APP + 1;

HINSTANCE g_instance = nullptr;

std::string to_utf8(const wchar_t* value, std::size_t length) {
    if (length == 0) {
        return {};
    }
    const int size = WideCharToMultiByte(CP_UTF8, 0, value, static_cast<int>(length),
                                         nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, static_cast<int>(length), result.data(), size,
                        nullptr, nullptr);
    return result;
}

std::string to_utf8(const std::wstring& value) {
    return to_utf8(value.data(), value.size());
}

std::wstring to_wide(const std::string& value, UINT code_page = CP_UTF8) {
    if (value.empty()) {
        return {};
    }
    const int size = MultiByteToWideChar(code_page, 0, value.data(),
                                         static_cast<int>(value.size()), nullptr, 0);
    std::wstring result(static_cast<std::size_t>(size), L'\0');
    MultiByteToWideChar(code_page, 0, value.data(), static_cast<int>(value.size()),
                        result.data(), size);
    return result;
}

std::string trimmed(const std::string& value) {
    const auto begin = value.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) {
        return {};
    }
    const auto end = value.find_last_not_of(" \t\r\n");
    return value.substr(begin, end - begin + 1);
}

bool iequals(const std::string& left, const std::string& right) {
    return dsn::upper_ascii(left) == dsn::upper_ascii(right);
}

bool is_truthy(const std::string& value) {
    const auto normalized = dsn::upper_ascii(trimmed(value));
    return normalized == "TRUE" || normalized == "1" || normalized == "YES";
}

std::string get_value(const dsn::Attributes& attrs, const char* key) {
    const auto entry = attrs.find(key);
    return entry == attrs.end() ? std::string{} : entry->second;
}

// Restores the installer config mode on every exit path.
class ConfigModeGuard {
public:
    explicit ConfigModeGuard(bool system_dsn) : active_(system_dsn) {
        if (active_) {
            SQLSetConfigMode(ODBC_SYSTEM_DSN);
        }
    }
    ~ConfigModeGuard() {
        if (active_) {
            SQLSetConfigMode(ODBC_BOTH_DSN);
        }
    }
    ConfigModeGuard(const ConfigModeGuard&) = delete;
    ConfigModeGuard& operator=(const ConfigModeGuard&) = delete;

private:
    bool active_;
};

void post_error(DWORD code, const std::string& message) {
    SQLPostInstallerErrorW(code, to_wide(message).c_str());
}

bool dsn_key_exists(const std::wstring& name, const std::wstring& key) {
    const wchar_t* missing = L"\x01";
    wchar_t buffer[8] = {};
    SQLGetPrivateProfileStringW(name.c_str(), key.c_str(), missing, buffer, 8, L"ODBC.INI");
    return std::wstring(buffer) != missing;
}

// Writes the stored keys of a DSN. Absent or empty values delete the key.
bool write_dsn(const std::wstring& name, const std::wstring& driver,
               const dsn::Attributes& attrs, std::string* error) {
    if (!SQLWriteDSNToIniW(name.c_str(), driver.c_str())) {
        *error = "Could not create the data source in ODBC.INI.";
        return false;
    }
    for (const auto& key : dsn::stored_keys()) {
        const auto value = trimmed(get_value(attrs, key.c_str()));
        const auto wide_key = to_wide(key == dsn::KEY_DESCRIPTION ? "Description" : key);
        const auto wide_value = to_wide(value);
        // Deleting a key that does not exist makes the installer API fail.
        if (value.empty() && !dsn_key_exists(name, wide_key)) {
            continue;
        }
        if (!SQLWritePrivateProfileStringW(name.c_str(), wide_key.c_str(),
                                           value.empty() ? nullptr : wide_value.c_str(),
                                           L"ODBC.INI")) {
            *error = "Could not write '" + key + "' to ODBC.INI.";
            return false;
        }
    }
    return true;
}

struct DialogState {
    std::wstring driver;
    std::string original_dsn;
    bool adding = true;
    dsn::Attributes attributes;
    bool testing = false;
    std::thread test_worker;
};

struct TestResult {
    bool success = false;
    std::wstring message;
};

std::string get_text(HWND dialog, int id) {
    const HWND control = GetDlgItem(dialog, id);
    const int length = GetWindowTextLengthW(control);
    std::wstring text(static_cast<std::size_t>(length) + 1, L'\0');
    const int copied = GetWindowTextW(control, text.data(), length + 1);
    return trimmed(to_utf8(text.data(), static_cast<std::size_t>(copied)));
}

void set_text(HWND dialog, int id, const std::string& value) {
    SetDlgItemTextW(dialog, id, to_wide(value).c_str());
}

void update_tls_controls(HWND dialog) {
    const BOOL enabled = IsDlgButtonChecked(dialog, IDC_TLS_ENABLED) == BST_CHECKED;
    for (const int id : {IDC_TLS_ROOTCERT, IDC_TLS_CERT, IDC_TLS_KEY, IDC_BROWSE_ROOTCERT,
                         IDC_BROWSE_CERT, IDC_BROWSE_KEY}) {
        EnableWindow(GetDlgItem(dialog, id), enabled);
    }
}

void load_dialog(HWND dialog, const DialogState& state) {
    const auto& attrs = state.attributes;
    set_text(dialog, IDC_DSN, get_value(attrs, dsn::KEY_DSN));
    set_text(dialog, IDC_DESCRIPTION, get_value(attrs, dsn::KEY_DESCRIPTION));
    set_text(dialog, IDC_SERVER, get_value(attrs, dsn::KEY_SERVER));
    set_text(dialog, IDC_DATABASE, get_value(attrs, dsn::KEY_DATABASE));
    set_text(dialog, IDC_UID, get_value(attrs, dsn::KEY_UID));
    set_text(dialog, IDC_DATASOURCE_NAME, get_value(attrs, dsn::KEY_DATASOURCE_NAME));
    set_text(dialog, IDC_RETRY_ATTEMPTS, get_value(attrs, dsn::KEY_RETRY_ATTEMPTS));
    set_text(dialog, IDC_RETRY_DELAY, get_value(attrs, dsn::KEY_RETRY_DELAY));
    set_text(dialog, IDC_TLS_ROOTCERT, get_value(attrs, dsn::KEY_TLS_ROOTCERT));
    set_text(dialog, IDC_TLS_CERT, get_value(attrs, dsn::KEY_TLS_CERT));
    set_text(dialog, IDC_TLS_KEY, get_value(attrs, dsn::KEY_TLS_KEY));
    CheckDlgButton(dialog, IDC_XA,
                   is_truthy(get_value(attrs, dsn::KEY_XA)) ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(dialog, IDC_TLS_ENABLED,
                   is_truthy(get_value(attrs, dsn::KEY_TLS_ENABLED)) ? BST_CHECKED
                                                                     : BST_UNCHECKED);
    const auto load_aware = trimmed(get_value(attrs, dsn::KEY_LOAD_AWARE));
    CheckDlgButton(dialog, IDC_LOAD_AWARE,
                   load_aware.empty() ? BST_INDETERMINATE
                                      : (is_truthy(load_aware) ? BST_CHECKED : BST_UNCHECKED));
    update_tls_controls(dialog);
}

// Builds the attribute set to persist from the dialog; omitted keys are deleted on write.
dsn::Attributes collect_attributes(HWND dialog) {
    dsn::Attributes attrs;
    const auto put = [&](const char* key, const std::string& value) {
        if (!value.empty()) {
            attrs[key] = value;
        }
    };
    put(dsn::KEY_DSN, get_text(dialog, IDC_DSN));
    put(dsn::KEY_DESCRIPTION, get_text(dialog, IDC_DESCRIPTION));
    put(dsn::KEY_SERVER, get_text(dialog, IDC_SERVER));
    put(dsn::KEY_DATABASE, get_text(dialog, IDC_DATABASE));
    put(dsn::KEY_UID, get_text(dialog, IDC_UID));
    put(dsn::KEY_DATASOURCE_NAME, get_text(dialog, IDC_DATASOURCE_NAME));
    put(dsn::KEY_RETRY_ATTEMPTS, get_text(dialog, IDC_RETRY_ATTEMPTS));
    put(dsn::KEY_RETRY_DELAY, get_text(dialog, IDC_RETRY_DELAY));
    if (IsDlgButtonChecked(dialog, IDC_XA) == BST_CHECKED) {
        attrs[dsn::KEY_XA] = "true";
    }
    const UINT load_aware = IsDlgButtonChecked(dialog, IDC_LOAD_AWARE);
    if (load_aware == BST_CHECKED) {
        attrs[dsn::KEY_LOAD_AWARE] = "true";
    } else if (load_aware == BST_UNCHECKED) {
        attrs[dsn::KEY_LOAD_AWARE] = "false";
    }
    if (IsDlgButtonChecked(dialog, IDC_TLS_ENABLED) == BST_CHECKED) {
        attrs[dsn::KEY_TLS_ENABLED] = "true";
        put(dsn::KEY_TLS_ROOTCERT, get_text(dialog, IDC_TLS_ROOTCERT));
        put(dsn::KEY_TLS_CERT, get_text(dialog, IDC_TLS_CERT));
        put(dsn::KEY_TLS_KEY, get_text(dialog, IDC_TLS_KEY));
    }
    return attrs;
}

int control_for_error(const std::string& message) {
    const struct {
        const char* text;
        int id;
    } map[] = {{"Server", IDC_SERVER},
               {"Database", IDC_DATABASE},
               {"Retry attempts", IDC_RETRY_ATTEMPTS},
               {"Retry delay", IDC_RETRY_DELAY},
               {"Client", IDC_TLS_CERT},
               {"TLS", IDC_TLS_ENABLED}};
    for (const auto& entry : map) {
        if (message.rfind(entry.text, 0) == 0) {
            return entry.id;
        }
    }
    return IDC_DSN;
}

void show_validation_error(HWND dialog, const std::string& message, int control_id) {
    MessageBoxW(dialog, to_wide(message).c_str(), L"OJP ODBC Data Source Setup",
                MB_OK | MB_ICONWARNING);
    SetFocus(GetDlgItem(dialog, control_id));
}

void browse_for_file(HWND dialog, int edit_id) {
    wchar_t path[MAX_PATH] = {};
    GetDlgItemTextW(dialog, edit_id, path, MAX_PATH);
    OPENFILENAMEW ofn = {};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = dialog;
    ofn.lpstrFilter = L"PEM files (*.pem;*.crt;*.key)\0*.pem;*.crt;*.key\0All files\0*.*\0";
    ofn.lpstrFile = path;
    ofn.nMaxFile = MAX_PATH;
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_HIDEREADONLY;
    if (GetOpenFileNameW(&ofn)) {
        SetDlgItemTextW(dialog, edit_id, path);
    }
}

std::string escape_braces(const std::string& value) {
    std::string out;
    for (const char c : value) {
        out.push_back(c);
        if (c == '}') {
            out.push_back('}');
        }
    }
    return out;
}

std::wstring collect_diagnostics(SQLSMALLINT handle_type, SQLHANDLE handle) {
    std::wstring messages;
    SQLWCHAR state[6] = {};
    SQLWCHAR text[1024] = {};
    SQLINTEGER native = 0;
    SQLSMALLINT length = 0;
    for (SQLSMALLINT index = 1;
         SQL_SUCCEEDED(SQLGetDiagRecW(handle_type, handle, index, state, &native, text,
                                      static_cast<SQLSMALLINT>(sizeof(text) / sizeof(text[0])),
                                      &length));
         ++index) {
        if (!messages.empty()) {
            messages += L"\n";
        }
        messages += L"[";
        messages += reinterpret_cast<const wchar_t*>(state);
        messages += L"] ";
        messages += reinterpret_cast<const wchar_t*>(text);
    }
    return messages;
}

TestResult run_connection_test(const std::wstring& connection_string) {
    TestResult result;
    SQLHENV env = SQL_NULL_HENV;
    SQLHDBC dbc = SQL_NULL_HDBC;
    if (!SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_ENV, SQL_NULL_HANDLE, &env))) {
        result.message = L"Could not allocate an ODBC environment.";
        return result;
    }
    SQLSetEnvAttr(env, SQL_ATTR_ODBC_VERSION, reinterpret_cast<SQLPOINTER>(SQL_OV_ODBC3), 0);
    if (!SQL_SUCCEEDED(SQLAllocHandle(SQL_HANDLE_DBC, env, &dbc))) {
        result.message = collect_diagnostics(SQL_HANDLE_ENV, env);
        SQLFreeHandle(SQL_HANDLE_ENV, env);
        return result;
    }
    std::wstring buffer = connection_string;
    SQLSMALLINT out_length = 0;
    const SQLRETURN rc = SQLDriverConnectW(
        dbc, nullptr, reinterpret_cast<SQLWCHAR*>(buffer.data()), SQL_NTS, nullptr, 0,
        &out_length, SQL_DRIVER_NOPROMPT);
    if (SQL_SUCCEEDED(rc)) {
        result.success = true;
        SQLDisconnect(dbc);
    } else {
        result.message = collect_diagnostics(SQL_HANDLE_DBC, dbc);
        if (result.message.empty()) {
            result.message = L"Connection failed.";
        }
    }
    SQLFreeHandle(SQL_HANDLE_DBC, dbc);
    SQLFreeHandle(SQL_HANDLE_ENV, env);
    return result;
}

void set_testing(HWND dialog, DialogState* state, bool testing) {
    state->testing = testing;
    for (const int id : {IDOK, IDCANCEL, IDC_TEST}) {
        EnableWindow(GetDlgItem(dialog, id), !testing);
    }
    SetCursor(LoadCursorW(nullptr, testing ? IDC_WAIT : IDC_ARROW));
}

void start_connection_test(HWND dialog, DialogState* state) {
    auto attrs = collect_attributes(dialog);
    const auto error = dsn::validate(attrs, true);
    if (!error.empty()) {
        show_validation_error(dialog, error, control_for_error(error));
        return;
    }
    attrs.erase(dsn::KEY_DSN);
    attrs.erase(dsn::KEY_DESCRIPTION);
    const auto connection_string = L"DRIVER={" + state->driver + L"};" +
        to_wide(dsn::to_connection_string(attrs)) + L"PWD={" +
        to_wide(escape_braces(get_text(dialog, IDC_PWD))) + L"};";

    set_testing(dialog, state, true);
    // The worker is joined when its result arrives, so no thread outlives the dialog
    // (the setup DLL may be unloaded right after ConfigDSN returns).
    try {
        state->test_worker = std::thread([dialog, connection_string]() {
            auto result = std::make_unique<TestResult>(run_connection_test(connection_string));
            if (PostMessageW(dialog, WM_TEST_FINISHED, 0,
                             reinterpret_cast<LPARAM>(result.get()))) {
                result.release();
            }
        });
    } catch (const std::system_error&) {
        set_testing(dialog, state, false);
        MessageBoxW(dialog, L"Could not start the connection test.",
                    L"OJP ODBC Data Source Setup", MB_OK | MB_ICONERROR);
    }
}

void finish_connection_test(HWND dialog, DialogState* state, LPARAM lparam) {
    std::unique_ptr<TestResult> result(reinterpret_cast<TestResult*>(lparam));
    if (state->test_worker.joinable()) {
        state->test_worker.join();
    }
    set_testing(dialog, state, false);
    if (result->success) {
        MessageBoxW(dialog, L"Connection successful.", L"Test Connection",
                    MB_OK | MB_ICONINFORMATION);
    } else {
        MessageBoxW(dialog, result->message.c_str(), L"Test Connection",
                    MB_OK | MB_ICONERROR);
    }
}

void accept_dialog(HWND dialog, DialogState* state) {
    auto attrs = collect_attributes(dialog);
    const auto name = get_value(attrs, dsn::KEY_DSN);
    if (name.empty()) {
        show_validation_error(dialog, "Data Source Name is required.", IDC_DSN);
        return;
    }
    if (!SQLValidDSNW(to_wide(name).c_str())) {
        show_validation_error(dialog, "The Data Source Name contains invalid characters.",
                              IDC_DSN);
        return;
    }
    const auto error = dsn::validate(attrs, true);
    if (!error.empty()) {
        show_validation_error(dialog, error, control_for_error(error));
        return;
    }
    const bool name_changed = !iequals(name, state->original_dsn);
    dsn::Attributes existing;
    std::string ignored;
    if ((state->adding || name_changed) && dsn::read_stored_dsn(name, &existing, &ignored)) {
        const auto question = "A data source named '" + name + "' already exists. Replace it?";
        if (MessageBoxW(dialog, to_wide(question).c_str(), L"OJP ODBC Data Source Setup",
                        MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2) != IDYES) {
            SetFocus(GetDlgItem(dialog, IDC_DSN));
            return;
        }
    }
    state->attributes = std::move(attrs);
    EndDialog(dialog, IDOK);
}

INT_PTR CALLBACK dialog_proc(HWND dialog, UINT message, WPARAM wparam, LPARAM lparam) {
    auto* state = reinterpret_cast<DialogState*>(GetWindowLongPtrW(dialog, DWLP_USER));
    switch (message) {
    case WM_INITDIALOG:
        state = reinterpret_cast<DialogState*>(lparam);
        SetWindowLongPtrW(dialog, DWLP_USER, reinterpret_cast<LONG_PTR>(state));
        load_dialog(dialog, *state);
        return TRUE;
    case WM_TEST_FINISHED:
        finish_connection_test(dialog, state, lparam);
        return TRUE;
    case WM_CLOSE:
        if (state != nullptr && !state->testing) {
            EndDialog(dialog, IDCANCEL);
        }
        return TRUE;
    case WM_SETCURSOR:
        if (state != nullptr && state->testing) {
            SetCursor(LoadCursorW(nullptr, IDC_WAIT));
            SetWindowLongPtrW(dialog, DWLP_MSGRESULT, TRUE);
            return TRUE;
        }
        return FALSE;
    case WM_COMMAND:
        if (state != nullptr && state->testing) {
            return TRUE;
        }
        switch (LOWORD(wparam)) {
        case IDOK:
            accept_dialog(dialog, state);
            return TRUE;
        case IDCANCEL:
            EndDialog(dialog, IDCANCEL);
            return TRUE;
        case IDC_TEST:
            start_connection_test(dialog, state);
            return TRUE;
        case IDC_TLS_ENABLED:
            update_tls_controls(dialog);
            return TRUE;
        case IDC_BROWSE_ROOTCERT:
            browse_for_file(dialog, IDC_TLS_ROOTCERT);
            return TRUE;
        case IDC_BROWSE_CERT:
            browse_for_file(dialog, IDC_TLS_CERT);
            return TRUE;
        case IDC_BROWSE_KEY:
            browse_for_file(dialog, IDC_TLS_KEY);
            return TRUE;
        default:
            return FALSE;
        }
    default:
        return FALSE;
    }
}

std::vector<std::string> split_attribute_list(LPCWSTR attributes) {
    std::vector<std::string> pairs;
    if (attributes == nullptr) {
        return pairs;
    }
    while (*attributes != L'\0') {
        const std::size_t length = wcslen(attributes);
        pairs.push_back(to_utf8(attributes, length));
        attributes += length + 1;
    }
    return pairs;
}

bool run_add_or_config(HWND parent, LPCWSTR driver, dsn::Attributes attrs, bool adding) {
    const auto name = trimmed(get_value(attrs, dsn::KEY_DSN));
    std::string error;

    if (!adding) {
        if (name.empty()) {
            post_error(ODBC_ERROR_INVALID_DSN, "The DSN attribute is required.");
            return false;
        }
        dsn::Attributes stored;
        if (!dsn::read_stored_dsn(name, &stored, &error)) {
            post_error(ODBC_ERROR_INVALID_DSN, error);
            return false;
        }
        attrs = dsn::merge(stored, attrs);
        attrs[dsn::KEY_DSN] = name;
    }

    std::string final_name = name;
    if (parent != nullptr) {
        DialogState state;
        state.driver = driver != nullptr ? driver : L"";
        state.original_dsn = adding ? std::string{} : name;
        state.adding = adding;
        state.attributes = std::move(attrs);
        const INT_PTR choice = DialogBoxParamW(g_instance, MAKEINTRESOURCEW(IDD_SETUP), parent,
                                               dialog_proc, reinterpret_cast<LPARAM>(&state));
        if (state.test_worker.joinable()) {
            state.test_worker.join();
        }
        if (choice != IDOK) {
            // A cancelled dialog is not an installer error; -1 means the dialog failed.
            if (choice == -1) {
                post_error(ODBC_ERROR_GENERAL_ERR, "Could not display the setup dialog.");
                return false;
            }
            return true;
        }
        attrs = std::move(state.attributes);
        final_name = get_value(attrs, dsn::KEY_DSN);
        if (!adding && !iequals(final_name, name)) {
            // Renamed: remove the old entry only after the new one is written.
            if (!write_dsn(to_wide(final_name), state.driver, attrs, &error)) {
                post_error(ODBC_ERROR_REQUEST_FAILED, error);
                return false;
            }
            SQLRemoveDSNFromIniW(to_wide(name).c_str());
            return true;
        }
    } else {
        if (name.empty()) {
            post_error(ODBC_ERROR_INVALID_DSN, "The DSN attribute is required.");
            return false;
        }
        if (!SQLValidDSNW(to_wide(name).c_str())) {
            post_error(ODBC_ERROR_INVALID_DSN, "The Data Source Name contains invalid characters.");
            return false;
        }
        error = dsn::validate(attrs, false);
        if (!error.empty()) {
            post_error(ODBC_ERROR_INVALID_KEYWORD_VALUE, error);
            return false;
        }
    }

    if (!write_dsn(to_wide(final_name), driver != nullptr ? driver : L"", attrs, &error)) {
        post_error(ODBC_ERROR_REQUEST_FAILED, error);
        return false;
    }
    return true;
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_instance = module;
        DisableThreadLibraryCalls(module);
    }
    return TRUE;
}

BOOL INSTAPI ConfigDSNW(HWND parent, WORD request, LPCWSTR driver, LPCWSTR attributes) {
    INITCOMMONCONTROLSEX controls = {sizeof(controls), ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&controls);

    auto attrs = dsn::parse_attribute_pairs(split_attribute_list(attributes));
    const bool system_dsn = request == ODBC_ADD_SYS_DSN || request == ODBC_CONFIG_SYS_DSN ||
        request == ODBC_REMOVE_SYS_DSN;
    ConfigModeGuard mode(system_dsn);

    switch (request) {
    case ODBC_ADD_DSN:
    case ODBC_ADD_SYS_DSN:
        return run_add_or_config(parent, driver, std::move(attrs), true) ? TRUE : FALSE;
    case ODBC_CONFIG_DSN:
    case ODBC_CONFIG_SYS_DSN:
        return run_add_or_config(parent, driver, std::move(attrs), false) ? TRUE : FALSE;
    case ODBC_REMOVE_DSN:
    case ODBC_REMOVE_SYS_DSN: {
        const auto name = trimmed(get_value(attrs, dsn::KEY_DSN));
        if (name.empty()) {
            post_error(ODBC_ERROR_INVALID_DSN, "The DSN attribute is required.");
            return FALSE;
        }
        if (!SQLRemoveDSNFromIniW(to_wide(name).c_str())) {
            post_error(ODBC_ERROR_REQUEST_FAILED, "Could not remove the data source.");
            return FALSE;
        }
        return TRUE;
    }
    default:
        post_error(ODBC_ERROR_INVALID_REQUEST_TYPE, "Unsupported configuration request.");
        return FALSE;
    }
}

BOOL INSTAPI ConfigDSN(HWND parent, WORD request, LPCSTR driver, LPCSTR attributes) {
    const std::wstring wide_driver = driver != nullptr ? to_wide(driver, CP_ACP) : L"";
    std::wstring wide_attributes;
    if (attributes != nullptr) {
        const char* cursor = attributes;
        while (*cursor != '\0') {
            const std::size_t length = strlen(cursor);
            wide_attributes += to_wide(std::string(cursor, length), CP_ACP);
            wide_attributes.push_back(L'\0');
            cursor += length + 1;
        }
    }
    wide_attributes.push_back(L'\0');
    return ConfigDSNW(parent, request, driver != nullptr ? wide_driver.c_str() : nullptr,
                      attributes != nullptr ? wide_attributes.c_str() : nullptr);
}
