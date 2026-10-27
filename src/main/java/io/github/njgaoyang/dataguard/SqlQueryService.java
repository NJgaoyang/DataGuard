package io.github.njgaoyang.dataguard;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * SQL V2: parse, authorize and execute a strictly bounded single-source SELECT.
 * No derived tables, subselects, CTEs, unions, wildcards, or write-capable SQL.
 * Each joined table receives its own independent policy request and audit record.
 */
@Service
class SqlQueryService {
 private final JdbcTemplate db;
 private final SourceApi sources;
 private final BusinessReadExecutor executor;
 private final PolicyEngine policy;
 private final Audit audit;
 private final SecurityControls controls;
 private final com.fasterxml.jackson.databind.ObjectMapper json;
 private final Set<String> approvedHosts;
 private final int timeout;
 private final Semaphore slots;
 private static final Pattern IDENT=Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
 private static final Pattern UNSAFE=Pattern.compile("(?is)(;|--|/\\*|\\*/|#|@|\\b(INTO|OUTFILE|DUMPFILE|PROCEDURE|FOR\\s+UPDATE|LOCK\\s+IN\\s+SHARE|UNION|INTERSECT|EXCEPT|WITH|RECURSIVE|LATERAL|EXISTS|OVER|WINDOW|QUALIFY)\\b)");
 private static final Set<String> NEVER=Set.of("SLEEP","BENCHMARK","LOAD_FILE","GET_LOCK",
    "RELEASE_LOCK","IS_FREE_LOCK","IS_USED_LOCK","MASTER_POS_WAIT","UUID_SHORT",
    "CONNECTION_ID","CURRENT_USER","SESSION_USER","USER","DATABASE","SCHEMA","VERSION","SYS_EVAL");
 SqlQueryService(JdbcTemplate db, SourceApi sources,BusinessReadExecutor executor,
    PolicyEngine policy,Audit audit,SecurityControls controls,com.fasterxml.jackson.databind.ObjectMapper json,
    @Value("${dataguard.allowed-source-hosts:}") String hosts,
    @Value("${dataguard.query-timeout-seconds:5}") int timeout,
    @Value("${dataguard.max-concurrency:4}") int concurrency){
  this.db=db;this.sources=sources;this.executor=executor;this.policy=policy;this.audit=audit;this.controls=controls;this.json=json;
  this.approvedHosts=new HashSet<>(Arrays.stream(hosts.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());
  this.timeout=Math.max(1,Math.min(30,timeout));
  this.slots=new Semaphore(Math.max(1,Math.min(32,concurrency)));
 }
 record Plan(String sql,String sourceId,List<Object> params,int limit,
   Map<String,Set<String>> columns,List<String> tables,Map<String,String> aliases,List<String> functions) {}
 private static ResponseStatusException invalid(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
 private static String ident(String s){
  if(s==null||!IDENT.matcher(s).matches())throw invalid("Invalid SQL identifier");
  return s;
 }
 private static String optionalAlias(Table t){
  return t.getAlias()==null?t.getName():ident(t.getAlias().getName());
 }
 private static int numericLimit(Expression exp,String name,int max){
  if(!(exp instanceof LongValue value)||value.getValue()<0||value.getValue()>max)
   throw invalid(name+" must be an integer literal within its configured maximum");
  return (int)value.getValue();
 }
 private static boolean hasEquijoin(Expression expression,Map<String,String> aliases){
  if(expression instanceof EqualsTo eq &&
     eq.getLeftExpression() instanceof Column left && eq.getRightExpression() instanceof Column right &&
     left.getTable()!=null && right.getTable()!=null &&
     left.getTable().getName()!=null && right.getTable().getName()!=null){
   String l=aliases.get(left.getTable().getName()),r=aliases.get(right.getTable().getName());
   return l!=null&&r!=null&&!l.equals(r);
  }
  if(expression instanceof AndExpression and)
   return hasEquijoin(and.getLeftExpression(),aliases)||hasEquijoin(and.getRightExpression(),aliases);
  if(expression instanceof Parenthesis p)return hasEquijoin(p.getExpression(),aliases);
  return false;
 }
 Plan plan(Map<String,Object> agent,Map<String,Object> args){
  Object rawSql=args.get("sql"),rawSource=args.get("sourceId");
  if(!(rawSql instanceof String sql)||sql.isBlank()||sql.length()>6000||
    !(rawSource instanceof String))throw invalid("sourceId and bounded SELECT sql required");
  String sourceId=Guard.id((String)rawSource);
  if(UNSAFE.matcher(sql).find()||!sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT "))
   throw invalid("Only safe SELECT statements are supported");
  Object paramArg=args.getOrDefault("params",List.of());
  if(!(paramArg instanceof List<?> p)||p.size()>40)throw invalid("Invalid parameter array");
  List<Object> parameters=new ArrayList<>();
  for(Object item:p){
   if(item!=null&&!(item instanceof String)&&!(item instanceof Boolean)&&!(item instanceof Number))
    throw invalid("Only scalar query parameters are supported");
   if(item instanceof String v&&v.length()>1024)throw invalid("Query parameter too large");
   parameters.add(item);
  }
  final PlainSelect select;
  try{
   Statement statement=CCJSqlParserUtil.parse(sql);
   if(!(statement instanceof PlainSelect plain))throw invalid("Only a plain SELECT is supported");
   select=plain;
  }catch(JSQLParserException ex){throw invalid("Invalid SELECT syntax");}
  if(select.getWithItemsList()!=null&&!select.getWithItemsList().isEmpty()||
     select.getIntoTables()!=null&&!select.getIntoTables().isEmpty()||
     select.getIntoTempTable()!=null||select.getForClause()!=null||
     select.getForMode()!=null||select.getForUpdateTable()!=null||
     select.getFetch()!=null||select.getLimitBy()!=null||select.getTop()!=null||
     select.getOracleHierarchical()!=null||select.getQualify()!=null||
     select.getWindowDefinitions()!=null&&!select.getWindowDefinitions().isEmpty()||
     select.getLateralViews()!=null&&!select.getLateralViews().isEmpty()||
     select.getOracleHint()!=null||select.getMySqlHintStraightJoin())
   throw invalid("Unsupported or unsafe SELECT clause");
  if(!(select.getFromItem() instanceof Table first)||first.getSchemaName()!=null)
   throw invalid("Only declared direct tables are allowed");
  List<Join> joins=select.getJoins()==null?List.of():select.getJoins();
  if(joins.size()>1)throw invalid("At most two tables can be joined");
  String firstName=ident(first.getName());
  Map<String,String> aliases=new LinkedHashMap<>();
  aliases.put(optionalAlias(first),firstName);
  List<String> tables=new ArrayList<>(List.of(firstName));
  for(Join join:joins){
   if(!(join.getRightItem() instanceof Table right)||right.getSchemaName()!=null)
    throw invalid("Only direct tables can be joined");
   String tableName=ident(right.getName()),alias=optionalAlias(right);
   if(aliases.putIfAbsent(alias,tableName)!=null)throw invalid("Duplicate table alias");
   if(join.isCross()||join.isNatural()||join.isSimple()||join.isApply()||join.isStraight()
      ||join.isWindowJoin()||join.isGlobal()||join.isSemi()||
      join.getUsingColumns()!=null&&!join.getUsingColumns().isEmpty())
    throw invalid("Unbounded or unsupported JOIN");
   if(join.getOnExpressions()==null||join.getOnExpressions().isEmpty()||join.getOnExpression()==null)
    throw invalid("JOIN ON required");
   tables.add(tableName);
  }
  if(new HashSet<>(tables).size()!=tables.size())throw invalid("Self JOIN is not enabled in SQL V2");
  var source=sources.sourceForRead(sourceId);
  if(!approvedHosts.contains(String.valueOf(source.get("host"))))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Source host not allowlisted");
  if(!joins.isEmpty()&&joins.get(0).isFull()&&!"STARROCKS".equals(source.get("type")))
   throw invalid("FULL OUTER JOIN requires a StarRocks source");
  if(joins.stream().anyMatch(j->j.isFull()||j.isLeft()||j.isRight())&&joins.size()>1)
   throw invalid("Invalid JOIN combination");
  // Query must never reference columns not explicitly allowed for this Agent.
  Map<String,Set<String>> granted=new LinkedHashMap<>();
  Map<String,Set<String>> masked=new LinkedHashMap<>();
  for(String table:tables){
   List<Map<String,Object>> rows=db.queryForList("""
    SELECT c.column_name AS columnName,c.masked
      FROM dg_agent_columns ac
      JOIN dg_agent_tables at ON at.agent_id=ac.agent_id AND at.source_id=ac.source_id AND at.table_name=ac.table_name
      JOIN dg_source_columns c ON c.source_id=ac.source_id AND c.table_name=ac.table_name AND c.column_name=ac.column_name
      JOIN dg_source_tables st ON st.source_id=ac.source_id AND st.table_name=ac.table_name
      JOIN dg_sources ds ON ds.id=ac.source_id
      JOIN dg_agents a ON a.id=ac.agent_id JOIN dg_users u ON u.id=a.owner_user_id
    WHERE ac.agent_id=? AND ac.source_id=? AND ac.table_name=?
      AND c.enabled=1 AND st.enabled=1 AND ds.enabled=1
      AND a.enabled=1 AND a.revoked_at IS NULL AND a.expires_at>NOW(3)
      AND u.enabled=1 AND u.must_change_password=0
    """,agent.get("id"),sourceId,table);
   if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Table is not authorized");
   granted.put(table,new HashSet<>(rows.stream().map(r->String.valueOf(r.get("columnName"))).toList()));
   Set<String> blocked=new HashSet<>();
   for(Map<String,Object> row:rows)
    if(row.get("masked") instanceof Number n&&n.intValue()!=0||Boolean.TRUE.equals(row.get("masked")))
     blocked.add(String.valueOf(row.get("columnName")));
   masked.put(table,blocked);
  }
  List<String> allowedFunctions=db.query("""
   SELECT function_name FROM dg_sql_functions WHERE enabled=1
   """,(rs,i)->rs.getString(1));
  Set<String> funcAllowed=new HashSet<>(allowedFunctions);
  Map<String,Set<String>> used=new LinkedHashMap<>();
  for(String table:tables)used.put(table,new TreeSet<>());
  Set<String> invoked=new TreeSet<>();
  Set<String> projectionAliases=new HashSet<>();
  final int[] bindCount={0};
  // Expression visitor covers nested arithmetic, CASE, function arguments, and predicates.
  var visitor=new ExpressionVisitorAdapter(){
   @Override public void visit(Column column){
    String col=ident(column.getColumnName());
    String table;
    if(column.getTable()!=null&&column.getTable().getName()!=null){
     table=aliases.get(ident(column.getTable().getName()));
     if(table==null)throw invalid("Unknown SQL table alias");
    }else {
     if(tables.size()>1){
      if(projectionAliases.contains(col))return; // ORDER BY SELECT alias
      throw invalid("JOIN columns must be qualified with table aliases");
     }
     table=tables.get(0);
     if(projectionAliases.contains(col)&&!granted.get(table).contains(col))return;
    }
    if(!granted.get(table).contains(col))
     throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Column not authorized: "+table+"."+col);
    if(masked.get(table).contains(col))
     throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Masked field is unavailable in dynamic SQL");
    used.get(table).add(col);
   }
   @Override public void visit(Function f){
    String name=ident(f.getName()).toUpperCase(Locale.ROOT);
    if(NEVER.contains(name)||!funcAllowed.contains(name)||f.getMultipartName()!=null&&f.getMultipartName().size()!=1||
       f.getNamedParameters()!=null||f.getKeep()!=null)
     throw new ResponseStatusException(HttpStatus.FORBIDDEN,"SQL function is not enabled");
    invoked.add(name);
    // COUNT(*) is an aggregate argument, not an unbounded SELECT * projection.
    if("COUNT".equals(name)&&f.getParameters()!=null&&f.getParameters().size()==1&&
       f.getParameters().get(0) instanceof AllColumns)return;
    super.visit(f);
   }
   @Override public void visit(ParenthesedSelect sub){throw invalid("Subqueries are not enabled");}
   @Override public void visit(Select sub){throw invalid("Subqueries are not enabled");}
   @Override public void visit(AllColumns star){throw invalid("SELECT * is prohibited");}
   @Override public void visit(AllTableColumns star){throw invalid("SELECT alias.* is prohibited");}
   @Override public void visit(AnalyticExpression ex){throw invalid("Window functions are not enabled");}
   @Override public void visit(JdbcParameter parameter){bindCount[0]++;}
  };
  if(select.getSelectItems()==null||select.getSelectItems().isEmpty()||select.getSelectItems().size()>80)
   throw invalid("Explicit SELECT expression list required");
  for(SelectItem<?> item:select.getSelectItems()){
   if(item.getExpression()==null)throw invalid("Invalid projection");
   if(item.getAlias()!=null)projectionAliases.add(ident(item.getAlias().getName()));
   if(item.getExpression() instanceof AllColumns||item.getExpression() instanceof AllTableColumns)
    throw invalid("Wildcard projection is prohibited");
   if(!(item.getExpression() instanceof Column)&&item.getAlias()==null)
    throw invalid("Calculated projections require an explicit AS alias");
   item.getExpression().accept(visitor);
  }
  // Aliases are legal only in HAVING/ORDER BY; WHERE/JOIN expressions still need actual column grants.
  Set<String> outputAliases=Set.copyOf(projectionAliases);
  projectionAliases.clear();
  for(Join join:joins){
   // Require a genuine table-to-table equality, not a tautology or a CROSS-like ON 1=1.
   var left=join.getOnExpression();
   if(!hasEquijoin(left,aliases)||java.util.regex.Pattern.compile("(?i)\\bOR\\b").matcher(left.toString()).find())
    throw invalid("JOIN requires an equality between columns of the two different tables, without OR");
   left.accept(visitor);
  }
  if(select.getWhere()!=null)select.getWhere().accept(visitor);
  projectionAliases.addAll(outputAliases);
  if(select.getGroupBy()!=null){
   if(select.getGroupBy().getGroupingSets()!=null&&!select.getGroupBy().getGroupingSets().isEmpty())
    throw invalid("GROUPING SETS are not enabled");
   if(select.getGroupBy().getGroupByExpressions()!=null)
    for(Object exp:select.getGroupBy().getGroupByExpressions()){
     if(!(exp instanceof Expression e))throw invalid("Invalid GROUP BY");
     e.accept(visitor);
    }
  }
  if(select.getHaving()!=null)select.getHaving().accept(visitor);
  if(select.getOrderByElements()!=null)for(OrderByElement o:select.getOrderByElements())o.getExpression().accept(visitor);
  if(bindCount[0]!=parameters.size())throw invalid("Query placeholders do not match params array");
  int max=controls.setting("max_rows_per_query",200);
  int limit;
  if(select.getLimit()==null){limit=Math.min(100,max);select.setLimit(new Limit().withRowCount(new LongValue(limit)));}
  else {
   Limit l=select.getLimit();
   if(l.isLimitAll()||l.isLimitNull()||l.getByExpressions()!=null&&!l.getByExpressions().isEmpty())
    throw invalid("Invalid LIMIT");
   limit=numericLimit(l.getRowCount(),"LIMIT",max);
   if(limit<1)throw invalid("LIMIT must be positive");
   if(l.getOffset()!=null)numericLimit(l.getOffset(),"OFFSET",1000000);
  }
  if(select.getOffset()!=null)numericLimit(select.getOffset().getOffset(),"OFFSET",1000000);
  if(limit>max)throw invalid("LIMIT exceeds configured quota");
  // COUNT(*) requires at least one authorized column on each source for table access checks.
  for(String table:tables)if(used.get(table).isEmpty())used.get(table).add(granted.get(table).stream().sorted().findFirst().orElseThrow());
  return new Plan(select.toString(),sourceId,Collections.unmodifiableList(new ArrayList<>(parameters)),limit,used,List.copyOf(tables),aliases,List.copyOf(invoked));
 }
 Map<String,Object> validate(Map<String,Object> agent,Map<String,Object> args){
  Plan plan=plan(agent,args);
  return Map.of("status","VALID","tables",plan.tables(),"columns",plan.columns(),
    "functions",plan.functions(),"effectiveLimit",plan.limit(),"sqlDialect","MYSQL_COMPATIBLE");
 }
 Map<String,Object> execute(Map<String,Object> agent,Map<String,Object> args){
  Plan plan=plan(agent,args);
  var source=sources.sourceForRead(plan.sourceId());
  // Every participating table must pass its own access rule; no JOIN bypass.
  List<PolicyEngine.Verdict> acquired=new ArrayList<>();
  boolean succeeded=false,locked=false;
  long started=System.nanoTime();
  try{
   String shape;
   try{shape="SQLV2|"+json.writeValueAsString(Map.of(
      "sql",plan.sql(),"params",plan.params(),"tables",plan.tables()));}
   catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw invalid("Invalid parameters");}
   String hash=PolicyEngine.fingerprint(String.valueOf(agent.get("id")),plan.sourceId(),"sqlv2",List.of(),plan.limit(),null,null,null,null,shape);
   for(String table:plan.tables()){
    PolicyEngine.Verdict verdict=policy.begin(agent,plan.sourceId(),table,
      plan.columns().get(table).stream().sorted().toList(),plan.limit(),
      null,null,null,null,hash);
    if("DENY".equals(verdict.action()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"JOIN table policy denied");
    acquired.add(verdict);
    if("APPROVAL".equals(verdict.action()))throw new PolicyEngine.Pending(verdict.id());
   }
   controls.chargeAgent(String.valueOf(agent.get("id")),plan.limit());
   if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Concurrent SQL queries limited");
   locked=true;
   List<Map<String,Object>> rows=executor.executeDynamic(source,plan.sql(),plan.params(),plan.limit(),timeout);
   // Do not release any buffered data after table or grant revocation.
   plan(agent,args);
   for(int i=0;i<plan.tables().size();i++){
    String table=plan.tables().get(i);
    policy.verifyStillAllowed(acquired.get(i),agent,plan.sourceId(),table,plan.columns().get(table).stream().sorted().toList(),plan.limit());
   }
   long ms=Duration.ofNanos(System.nanoTime()-started).toMillis();
   for(int i=0;i<plan.tables().size();i++)
    policy.complete(acquired.get(i),rows.size(),ms,String.valueOf(agent.get("name")),plan.sourceId()+"/"+plan.tables().get(i));
   succeeded=true;
   return Map.of("status","SUCCESS","operation","SQL_READ","rowCount",rows.size(),"rows",rows,
    "requestIds",acquired.stream().map(PolicyEngine.Verdict::id).toList());
  }catch(SQLException ex){
   audit.log(String.valueOf(agent.get("name")),"SQL_READ",null,plan.sourceId(),"ERROR","SQL execution error");
   throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Read-only SQL execution failed");
  }finally{
   if(locked)slots.release();
   if(!succeeded)for(PolicyEngine.Verdict v:acquired)policy.fail(v);
  }
 }
}
