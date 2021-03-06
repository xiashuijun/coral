/**
 * Copyright 2019 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.presto.rel2presto;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.linkedin.coral.com.google.common.base.Function;
import com.linkedin.coral.hive.hive2rel.HiveMetastoreClient;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;
import com.linkedin.coral.hive.hive2rel.HiveMscAdapter;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Programs;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.session.SessionState;

import static com.linkedin.coral.presto.rel2presto.TestTable.*;


public class TestUtils {

  static HiveToRelConverter hiveToRelConverter;

  public static FrameworkConfig createFrameworkConfig(TestTable... tables) {
    SchemaPlus rootSchema = Frameworks.createRootSchema(true);
    Arrays.asList(tables)
        .forEach(t -> rootSchema.add(t.getTableName(), t));

    SqlParser.Config parserConfig =
        SqlParser.configBuilder()
            .setCaseSensitive(false)
            .setConformance(SqlConformanceEnum.DEFAULT)
            .build();

    return Frameworks.newConfigBuilder()
        .parserConfig(parserConfig)
        .defaultSchema(rootSchema)
        .traitDefs((List<RelTraitDef>) null)
        .programs(Programs.ofRules(Programs.RULE_SET))
        .build();
  }

  static RelNode toRel(String sql, FrameworkConfig config) {
    Planner planner = Frameworks.getPlanner(config);
    try {
      SqlNode sn = planner.parse(sql);
      SqlNode validate = planner.validate(sn);
      RelRoot rel = planner.rel(validate);
      //RelNode relNode = rel.project();
      return rel.project();
      //return Calcite2PrestoUDFConverter.convertRel(relNode);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Utility to conveniently format sql string just like Calcite formatting.
   * This is used to conveniently generate expected string without having
   * to type all formatting each time.
   * WARNING: This is not generic method but utility method for common cases
   * for testing. There are bugs. Use at your own risk or, better yet, fix it
   * @param sql
   */
  public static String formatSql(String sql) {
    String s = upcaseKeywords(sql);
    s = quoteColumns(s);
    s = addLineBreaks(s);
    return s;
  }

  private static final ImmutableList<String> SQL_KEYWORDS;
  private static final Pattern KW_PATTERN;
  static {
    String kw = "SELECT FROM WHERE AS IN GROUP BY HAVING ORDER ASC DSC JOIN"
        + " INNER OUTER CROSS UNNEST LEFT RIGHT SUM COUNT MAX MIN AVG CAST IN EXCEPT IS NULL NOT OVER AND OR ON";
    SQL_KEYWORDS = ImmutableList.copyOf(kw.toLowerCase().split("\\s+"));
    KW_PATTERN = Pattern.compile("\\b(" + String.join("|", SQL_KEYWORDS) + ")");
  }

  private static String addLineBreaks(String s) {
    ImmutableList<String> lineBreakKeywords = ImmutableList.copyOf(
        "SELECT FROM WHERE GROUP HAVING ORDER OVER IN EXCEPT UNION INTERSECT".split("\\s+"));
    Pattern pattern = Pattern.compile("\\s" + String.join("\\b|\\s", lineBreakKeywords) + "\\b");
    Matcher m = pattern.matcher(s);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      m.appendReplacement(sb, "\n" + m.group().trim());
    }
    m.appendTail(sb);
    return sb.toString();
  }

  public static String quoteColumns(String sql) {
    Iterable<String> concat = Iterables.concat(
        TABLE_ONE.getColumnNames(),
        TABLE_TWO.getColumnNames(),
        TABLE_THREE.getColumnNames(),
        TABLE_FOUR.getColumnNames(),
        ImmutableList.of(
            TABLE_ONE.getTableName(),
            TABLE_TWO.getTableName(),
            TABLE_THREE.getTableName(),
            TABLE_FOUR.getTableName()));
    Pattern colPattern = Pattern.compile(String.join("|", concat));
    return quoteColumns(sql, colPattern);
  }

  public String quoteColumns(String sql, List<String> columns) {
    return quoteColumns(sql, Pattern.compile(String.join("|", columns)));
  }

  public static String quoteColumns(String sql, Pattern pattern) {
    String s = quoteAliases(sql);
    Matcher m = pattern.matcher(s);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      m.appendReplacement(sb, "\"" + m.group() + "\"");
    }
    m.appendTail(sb);
    return sb.toString();
  }

  public static String quoteAliases(String input) {
    Pattern pattern = Pattern.compile("AS (\\w+)");
    Matcher m = pattern.matcher(input);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String replacement = null;
      String alias = m.group(1);
      if (alias.equalsIgnoreCase("integer") ||
          alias.equalsIgnoreCase("double") ||
          alias.equalsIgnoreCase("boolean") ||
          alias.equalsIgnoreCase("varchar") ||
          alias.equalsIgnoreCase("real") ||
          alias.equalsIgnoreCase("varbinary")) {
        replacement = "AS " + alias.toUpperCase();
      } else {
        replacement = "AS \"" + m.group(1).toUpperCase() + "\"";
      }
      m.appendReplacement(sb, replacement);
    }
    m.appendTail(sb);
    return sb.toString();
  }

  public static String upcaseKeywords(String sql) {
    Matcher matcher = KW_PATTERN.matcher(sql);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(sb, matcher.group().toUpperCase());
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  static void run(Driver driver, String sql){
    while(true){
      try {
        driver.run(sql);
      } catch (CommandNeedRetryException e) {
        continue;
      }
      break;
    }
  }

  static void turnOffRelSimplification() {
    // Rel simplification can statically interpret boolean conditions in
    // OR, AND, CASE clauses and simplify those. This has two problems:
    // 1. Our type system is not perfect replication of Hive so this can be incorrect
    // 2. Converted expression is harder to validate for correctness(because it appears different from input)
    Hook.REL_BUILDER_SIMPLIFY.add(Hook.propertyJ(false));
  }

  public static void initializeViews() throws HiveException, MetaException {
    HiveConf conf = loadResourceHiveConf();
    SessionState.start(conf);
    Driver driver = new Driver(conf);
    HiveMetastoreClient hiveMetastoreClient = new HiveMscAdapter(Hive.get(conf).getMSC());
    hiveToRelConverter = HiveToRelConverter.create(hiveMetastoreClient);

    // Views and tables used in FuzzyUnionViewTest
    run(driver, String.join("\n", "",
        "CREATE DATABASE IF NOT EXISTS fuzzy_union"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableA(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view",
        "AS",
        "SELECT * from fuzzy_union.tableA",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableA"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_with_more_than_two_tables",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableA",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableA",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableA"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_with_alias",
        "AS",
        "SELECT *",
        "FROM",
        "(SELECT * from fuzzy_union.tableA)",
        "as viewFirst",
        "union all",
        "SELECT *",
        "FROM (SELECT * from fuzzy_union.tableA)",
        "as viewSecond"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableB(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableC(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_single_branch_evolved",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableB",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableC"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableC CHANGE COLUMN b b struct<b1:string, b2:int>"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableD(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableE(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_double_branch_evolved_same",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableD",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableE"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableD CHANGE COLUMN b b struct<b1:string, b2:int>"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableE CHANGE COLUMN b b struct<b1:string, b2:int>"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableF(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableG(a int, b struct<b1:string>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_double_branch_evolved_different",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableF",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableG"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_more_than_two_branches_evolved",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableF",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableG",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableF"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableF CHANGE COLUMN b b struct<b1:string, b3:string>"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableG CHANGE COLUMN b b struct<b1:string, b2:int>"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableH(a int, b map<string, struct<b1:string>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableI(a int, b map<string, struct<b1:string>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_map_with_struct_value_evolved",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableH",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableI"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableH CHANGE COLUMN b b map<string, struct<b1:string, b2:int>>"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableJ(a int, b array<struct<b1:string>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableK(a int, b array<struct<b1:string>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_array_with_struct_value_evolved",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableJ",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableK"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableJ CHANGE COLUMN b b array<struct<b1:string, b2:int>>"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableL(a int, b struct<b1:string, b2:struct<b3:string, b4:struct<b5:string>>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableM(a int, b struct<b1:string, b2:struct<b3:string, b4:struct<b5:string>>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_deeply_nested_struct_evolved",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableL",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableM"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableL CHANGE COLUMN b b struct<b1:string, b2:struct<b3:string, b4:struct<b5:string, b6:string>>>"
    ));

    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableN(a int, b struct<b1:string, m1:map<string, struct<b1:string, a1:array<struct<b1:string>>>>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE TABLE IF NOT EXISTS fuzzy_union.tableO(a int, b struct<b1:string, m1:map<string, struct<b1:string, a1:array<struct<b1:string>>>>>)"
    ));
    run(driver, String.join("\n", "",
        "CREATE VIEW IF NOT EXISTS fuzzy_union.union_view_deeply_nested_complex_struct_evolved",
        "AS",
        "SELECT *",
        "from fuzzy_union.tableN",
        "union all",
        "SELECT *",
        "from fuzzy_union.tableO"
    ));
    run(driver, String.join("\n", "",
        "ALTER TABLE fuzzy_union.tableN CHANGE COLUMN b b struct<b1:string, m1:map<string, struct<b1:string, a1:array<struct<b0:string, b1:string>>>>>"
    ));

  }

  public static RelNode convertView(String db, String view) {
    return hiveToRelConverter.convertView(db, view);
  }

  private static HiveConf loadResourceHiveConf() {
    InputStream hiveConfStream = TestUtils.class.getClassLoader().getResourceAsStream("hive.xml");
    HiveConf hiveConf = new HiveConf();
    hiveConf.addResource(hiveConfStream);
    hiveConf.set("mapreduce.framework.name", "local-presto");
    hiveConf.set("_hive.hdfs.session.path", "/tmp/coral/presto");
    hiveConf.set("_hive.local.session.path", "/tmp/coral/presto");
    return hiveConf;
  }
}
