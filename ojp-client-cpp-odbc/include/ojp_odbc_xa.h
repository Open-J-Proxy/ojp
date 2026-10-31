#ifndef OJP_ODBC_XA_H
#define OJP_ODBC_XA_H

#include <sql.h>

#include <stdint.h>

#if defined(_WIN32)
#if defined(OJP_ODBC_BUILD_DLL)
#define OJP_ODBC_XA_API __declspec(dllexport)
#else
#define OJP_ODBC_XA_API __declspec(dllimport)
#endif
#else
#define OJP_ODBC_XA_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define OJP_XA_MAX_GTRID_SIZE 64
#define OJP_XA_MAX_BQUAL_SIZE 64

#define OJP_XA_TMNOFLAGS 0x00000000
#define OJP_XA_TMJOIN 0x00200000
#define OJP_XA_TMRESUME 0x08000000
#define OJP_XA_TMSUCCESS 0x04000000
#define OJP_XA_TMFAIL 0x20000000
#define OJP_XA_TMSUSPEND 0x02000000
#define OJP_XA_TMSTARTRSCAN 0x01000000
#define OJP_XA_TMENDRSCAN 0x00800000
#define OJP_XA_XA_OK 0
#define OJP_XA_XA_RDONLY 3
#define OJP_XA_XAER_RMERR -3
#define OJP_XA_XAER_NOTA -4
#define OJP_XA_XAER_RMFAIL -7

typedef struct OjpXid {
    int32_t format_id;
    uint32_t global_transaction_id_length;
    uint8_t global_transaction_id[OJP_XA_MAX_GTRID_SIZE];
    uint32_t branch_qualifier_length;
    uint8_t branch_qualifier[OJP_XA_MAX_BQUAL_SIZE];
} OjpXid;

OJP_ODBC_XA_API SQLRETURN SQL_API OjpXAStart(SQLHDBC connection, const OjpXid* xid,
                                             SQLINTEGER flags);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXAEnd(SQLHDBC connection, const OjpXid* xid,
                                           SQLINTEGER flags);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXAPrepare(SQLHDBC connection, const OjpXid* xid,
                                               SQLINTEGER* result);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXACommit(SQLHDBC connection, const OjpXid* xid,
                                              SQLSMALLINT one_phase);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXARollback(SQLHDBC connection, const OjpXid* xid);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXARecover(SQLHDBC connection, SQLINTEGER flags,
                                               OjpXid* xids, SQLSMALLINT capacity,
                                               SQLSMALLINT* count);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXAForget(SQLHDBC connection, const OjpXid* xid);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXASetTransactionTimeout(SQLHDBC connection,
                                                             SQLINTEGER seconds,
                                                             SQLSMALLINT* success);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXAGetTransactionTimeout(SQLHDBC connection,
                                                             SQLINTEGER* seconds);
OJP_ODBC_XA_API SQLRETURN SQL_API OjpXAIsSameRM(SQLHDBC connection,
                                                SQLHDBC other_connection,
                                                SQLSMALLINT* same_resource_manager);

#ifdef __cplusplus
}
#endif

#endif
