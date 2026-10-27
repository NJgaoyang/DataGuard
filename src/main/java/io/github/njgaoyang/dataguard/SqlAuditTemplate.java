package io.github.njgaoyang.dataguard;

/**
 * Display-only SQL audit template from a previously parsed, authorized SQL statement.
 * Quoted and unquoted literal values are removed; JDBC bound parameters are never inserted.
 * Executed SQL is never modified.
 */
final class SqlAuditTemplate {
 private SqlAuditTemplate(){}
 static String redact(String sql){
  if(sql==null||sql.length()>12000)throw new IllegalArgumentException("Invalid SQL audit input");
  StringBuilder result=new StringBuilder(sql.length());
  int i=0,n=sql.length();
  while(i<n){
   char c=sql.charAt(i);
   if(c==39||c==34){
    char quote=c;
    i++;
    while(i<n){
     char ch=sql.charAt(i++);
     if(ch==92&&i<n){i++;continue;}
     if(ch==quote){
      if(i<n&&sql.charAt(i)==quote){i++;continue;}
      break;
     }
    }
    result.append("'[REDACTED]'");
    continue;
   }
   boolean wordBefore=i>0&&(Character.isLetterOrDigit(sql.charAt(i-1))||sql.charAt(i-1)=='_');
   if(Character.isDigit(c)&&!wordBefore){
    int j=i;
    if(c=='0'&&j+2<n&&(sql.charAt(j+1)=='x'||sql.charAt(j+1)=='X')){
     j+=2;
     while(j<n&&Character.digit(sql.charAt(j),16)>=0)j++;
    }else{
     while(j<n&&Character.isDigit(sql.charAt(j)))j++;
     if(j<n&&sql.charAt(j)=='.'){
      j++;
      while(j<n&&Character.isDigit(sql.charAt(j)))j++;
     }
     if(j<n&&(sql.charAt(j)=='e'||sql.charAt(j)=='E')){
      int exp=j+1;
      if(exp<n&&(sql.charAt(exp)=='+'||sql.charAt(exp)=='-'))exp++;
      int begin=exp;
      while(exp<n&&Character.isDigit(sql.charAt(exp)))exp++;
      if(exp>begin)j=exp;
     }
    }
    if(j==n||!Character.isLetterOrDigit(sql.charAt(j))&&sql.charAt(j)!='_'){
     result.append('?');i=j;continue;
    }
   }
   result.append(c);i++;
  }
  return result.toString();
 }
}