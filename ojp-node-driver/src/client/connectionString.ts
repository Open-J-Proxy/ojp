/**
 * Parser for the connection string in the OJP format:
 *   jdbc:ojp[host1:port1,host2:port2(dataSourceName)]_backendJdbcUrl
 *
 * Valid examples:
 *   jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb
 *   jdbc:ojp[localhost:1059(mainApp)]_postgresql://user@localhost/mydb
 *   jdbc:ojp[host1:1059,host2:1059]_mysql://localhost:3306/mydb
 */

export interface OjpServerEndpoint {
  host: string;
  port: number;
}

export interface ParsedOjpUrl {
  /** ojp-server endpoint(s), for failover/load-balancing in multinode mode. */
  endpoints: OjpServerEndpoint[];
  /** Optional datasource name, used to isolate named pools on the ojp-server. */
  dataSourceName?: string;
  /** Actual database JDBC URL, passed through to the ojp-server (e.g. postgresql://host/db). */
  backendUrl: string;
}

const OJP_URL_PATTERN = /^jdbc:ojp\[([^\]]+)\]_(.+)$/;

export function parseOjpUrl(url: string): ParsedOjpUrl {
  const match = OJP_URL_PATTERN.exec(url.trim());
  if (!match) {
    throw new Error(
      `Invalid OJP URL: "${url}". Expected format: jdbc:ojp[host:port(dataSourceName)]_backendJdbcUrl`,
    );
  }

  const [, endpointsSection, backendUrl] = match;
  const dataSourceMatch = /\(([^)]+)\)$/.exec(endpointsSection);
  const dataSourceName = dataSourceMatch ? dataSourceMatch[1] : undefined;
  const endpointsWithoutDataSource = dataSourceMatch
    ? endpointsSection.slice(0, dataSourceMatch.index)
    : endpointsSection;

  const endpoints = endpointsWithoutDataSource.split(',').map((entry) => {
    const [host, portStr] = entry.trim().split(':');
    const port = Number(portStr);
    if (!host || Number.isNaN(port)) {
      throw new Error(`Invalid OJP endpoint: "${entry}" inside "${url}"`);
    }
    return { host, port };
  });

  if (endpoints.length === 0) {
    throw new Error(`No ojp-server endpoint found in "${url}"`);
  }

  return { endpoints, dataSourceName, backendUrl };
}
