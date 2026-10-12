public class N { public static void main(String[] a) throws Exception {
 System.out.println(new org.mariadb.jdbc.MariaDbDataSource("jdbc:mariadb://x/y").getClass());
 java.sql.Driver d=new com.mysql.cj.jdbc.Driver();
 System.out.println(com.mysql.cj.Constants.CJ_NAME);
 System.out.println(org.mariadb.jdbc.util.VersionFactory.getInstance().getVersion());
}}
