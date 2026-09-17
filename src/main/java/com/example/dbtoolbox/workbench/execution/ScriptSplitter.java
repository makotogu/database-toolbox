package com.example.dbtoolbox.workbench.execution;

import com.example.dbtoolbox.common.AppException;
import java.util.*;
import java.util.regex.*;

/** A delimiter lexer, not a SQL grammar. Ambiguous procedure bodies require an explicit delimiter. */
public final class ScriptSplitter {
    public static class Unit {
        public String sql;
        public int startOffset, endOffset, startLine, endLine;
        Unit(String source,int start,int end) {
            while (start<end && Character.isWhitespace(source.charAt(start))) start++;
            while (end>start && Character.isWhitespace(source.charAt(end-1))) end--;
            sql=source.substring(start,end); startOffset=start; endOffset=end;
            startLine=line(source,start); endLine=line(source,Math.max(start,end-1));
        }
    }
    private static final Pattern DOLLAR = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$");
    private static final Pattern DIRECTIVE = Pattern.compile("(?i)DELIMITER\\s+(\\S+)\\s*");
    private ScriptSplitter() { }
    public static List<Unit> split(String source,String dialect) { return split(source,dialect,false); }
    public static List<Unit> split(String source,String dialect,boolean backslashEscapes) {
        if (source==null || source.length()>1024*1024) throw new AppException("SQL 不能为空且不能超过 1 MiB");
        boolean mysql="MYSQL".equals(dialect), oracle="ORACLE".equals(dialect)||"GAUSSDB".equals(dialect);
        List<Unit> units=new ArrayList<Unit>(); String delimiter=";", dollar=null;
        int start=0,i=0,blockDepth=0; char quote=0,qEnd=0; boolean lineComment=false,procedural=false,quoteEscapes=false;
        while(i<source.length()) {
            char c=source.charAt(i), next=i+1<source.length()?source.charAt(i+1):0;
            if (lineComment) { if(c=='\n') lineComment=false; i++; continue; }
            if (blockDepth>0) {
                if(c=='/'&&next=='*'&&!mysql) { blockDepth++; i+=2; }
                else if(c=='*'&&next=='/') { blockDepth--;i+=2; } else i++;
                continue;
            }
            if(dollar!=null) { if(source.startsWith(dollar,i)) { i+=dollar.length();dollar=null; } else i++;continue; }
            if(qEnd!=0) { if(c==qEnd&&next=='\'') { qEnd=0;i+=2; } else i++;continue; }
            if(quote!=0) {
                if(c=='\\'&&quoteEscapes&&(quote=='\''||quote=='"')) { i+=Math.min(2,source.length()-i);continue; }
                if(c==quote) { if(next==quote) i+=2; else {quote=0;i++;} } else i++;
                continue;
            }
            if(i==0 || source.charAt(i-1)=='\n') {
                int end=source.indexOf('\n',i);if(end<0)end=source.length();
                String line=source.substring(i,end).trim();
                Matcher command=DIRECTIVE.matcher(line);
                if(mysql&&command.matches()) {
                    if(hasContent(source.substring(start,i))) throw error(source,i,"DELIMITER 必须在语句边界使用");
                    delimiter=command.group(1);
                    if(delimiter.length()>16 || delimiter.contains("'") || delimiter.contains("\"")) throw error(source,i,"不支持的 DELIMITER");
                    i=end<source.length()?end+1:end;start=i;continue;
                }
                if(oracle && "/".equals(line)) {
                    add(units,source,start,i); i=end<source.length()?end+1:end;start=i;procedural=false;continue;
                }
            }
            if(c=='-'&&next=='-'&&(!mysql || i+2==source.length() || Character.isWhitespace(source.charAt(i+2)))) { lineComment=true;i+=2;continue; }
            if(mysql&&c=='#') { lineComment=true;i++;continue; }
            if(c=='/'&&next=='*') {blockDepth=1;i+=2;continue;}
            if(oracle&&(c=='q'||c=='Q')&&next=='\''&&i+2<source.length()) {
                char opener=source.charAt(i+2);qEnd=opener=='['?']':opener=='('?')':opener=='{'?'}':opener=='<'?'>':opener;i+=3;continue;
            }
            if(c=='\''||c=='"'||c=='`') { quote=c;quoteEscapes=backslashEscapes || (!mysql&&c=='\''&&i>0&&(source.charAt(i-1)=='e'||source.charAt(i-1)=='E')&&(i<2||!Character.isJavaIdentifierPart(source.charAt(i-2))));i++;continue; }
            if(!mysql&&c=='$'&&(i==0||(!Character.isJavaIdentifierPart(source.charAt(i-1))&&source.charAt(i-1)!='$'))) { Matcher m=DOLLAR.matcher(source);m.region(i,source.length());if(m.lookingAt()){dollar=m.group();i+=dollar.length();continue;} }
            if(source.startsWith(delimiter,i)) {
                String leading=withoutLeadingComments(source.substring(start,i)).replaceAll("(?s)/\\*.*?\\*/"," ").trim().toUpperCase(Locale.ROOT);
                if(oracle && (leading.startsWith("DECLARE") || leading.matches("(?s)BEGIN\\b.+") || leading.matches("(?s)CREATE\\s+(OR\\s+REPLACE\\s+)?((NON)?EDITIONABLE\\s+)?(PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE\\s+BODY)\\b.*"))) procedural=true;
                if(mysql&&";".equals(delimiter)&&leading.matches("(?s)CREATE\\s+(DEFINER\\s*=.+?\\s+)?(PROCEDURE|FUNCTION|TRIGGER|EVENT)\\b.*\\bBEGIN\\b.*"))
                    throw error(source,start,"含过程体的 MySQL 脚本请使用 DELIMITER，或选中过程体后整块执行");
                if(!procedural) {add(units,source,start,i);i+=delimiter.length();start=i;continue;}
            }
            i++;
        }
        if(quote!=0||qEnd!=0||dollar!=null||blockDepth>0) throw error(source,source.length(),"字符串、注释或代码块引号未闭合");
        if(procedural&&hasContent(source.substring(start))) throw error(source,start,"过程块脚本需要独占一行 / 结束，或使用整块执行");
        add(units,source,start,source.length());
        if(units.isEmpty()) throw new AppException("没有可执行的 SQL");
        if(units.size()>500) throw new AppException("一次脚本最多 500 个执行单元");
        return units;
    }
    public static Unit block(String source,String dialect) {
        if(source==null||source.length()>1024*1024) throw new AppException("SQL 不能为空且不能超过 1 MiB");
        int end=source.length();
        if("ORACLE".equals(dialect)||"GAUSSDB".equals(dialect)) {
            // Reuse the lexical scanner to distinguish a client slash from one inside q-quoted text.
            if(Pattern.compile("(?m)^\\s*/\\s*$").matcher(source).find()) {
                List<Unit> parsed=split(source+"\n/",dialect);
                if(parsed.size()!=1)throw new AppException("整块模式仅接受一个块，多个块请用脚本模式");
                return parsed.get(0);
            }
        }
        if(!hasContent(source.substring(0,end)))throw new AppException("没有可执行的 SQL");
        return new Unit(source,0,end);
    }
    public static boolean requiresConfirmation(String sql) { return requiresConfirmation(sql,"GENERIC",false); }
    public static boolean requiresConfirmation(String sql,String dialect,boolean backslashes) {
        String s=confirmationCode(sql,dialect,backslashes);
        if(s==null)return true;
        s=s.trim().toLowerCase(Locale.ROOT);
        if(s.matches("(?s)explain\\b.*"))return Pattern.compile("(?is)\\banaly[sz]e\\b").matcher(s).find() || !s.matches("(?s)explain\\s+(?:\\([^)]*\\)\\s*)?(?:format\\s*=\\s*\\w+\\s+)?select\\b.*");
        if(s.matches("(?s)select\\b.*"))
            return Pattern.compile("(?is)\\binto\\b|\\bfor\\s+(?:(?:no\\s+key|key)\\s+)?(?:update|share)\\b|\\block\\s+in\\s+share\\s+mode\\b").matcher(s).find();
        return !s.matches("(?s)(show|describe|desc)\\b.*");
    }
    /** Preserve code tokens only; quoted text is a barrier, comments are whitespace. Not a SQL grammar. */
    private static String confirmationCode(String source,String dialect,boolean backslashes) {
        if(source==null)return null;
        boolean mysql="MYSQL".equals(dialect),oracle="ORACLE".equals(dialect);
        StringBuilder code=new StringBuilder();int i=0;
        while(i<source.length()) {
            char c=source.charAt(i),next=i+1<source.length()?source.charAt(i+1):0;
            if((c=='-'&&next=='-'&&(!mysql||i+2==source.length()||Character.isWhitespace(source.charAt(i+2))))||(mysql&&c=='#')) {
                int end=source.indexOf('\n',i);i=end<0?source.length():end;code.append(' ');continue;
            }
            if(c=='/'&&next=='*') {
                if(i+2<source.length()&&(source.charAt(i+2)=='!' || source.startsWith("/*M!",i)))return null;
                int depth=1;i+=2;
                while(i<source.length()&&depth>0){
                    if(source.startsWith("/*",i)&&!mysql){depth++;i+=2;}
                    else if(source.startsWith("*/",i)){depth--;i+=2;}else i++;
                }
                if(depth!=0)return null;code.append(' ');continue;
            }
            // GAUSSDB alone does not identify the database's SQL compatibility mode.
            // Confirm ambiguous q-quote forms rather than assuming Oracle or PostgreSQL semantics.
            if("GAUSSDB".equals(dialect)&&(c=='q'||c=='Q')&&next=='\'')return null;
            if(oracle&&(c=='q'||c=='Q')&&next=='\''&&i+2<source.length()) {
                char open=source.charAt(i+2),end=open=='['?']':open=='('?')':open=='{'?'}':open=='<'?'>':open;
                int finish=source.indexOf(""+end+'\'',i+3);if(finish<0)return null;
                i=finish+2;code.append(" ? ");continue;
            }
            if(!mysql&&c=='$'&&(i==0||!Character.isJavaIdentifierPart(source.charAt(i-1)))) {
                Matcher m=DOLLAR.matcher(source);m.region(i,source.length());
                if(m.lookingAt()) {String delimiter=m.group();int end=source.indexOf(delimiter,i+delimiter.length());if(end<0)return null;i=end+delimiter.length();code.append(" ? ");continue;}
            }
            if(c=='\''||c=='"'||c=='`') {
                char quote=c;boolean escapes=backslashes||(!mysql&&c=='\''&&i>0&&(source.charAt(i-1)=='e'||source.charAt(i-1)=='E')&&(i<2||!Character.isJavaIdentifierPart(source.charAt(i-2))));
                boolean ended=false;i++;
                while(i<source.length()) {
                    char ch=source.charAt(i++);
                    if(ch=='\\'&&escapes){if(i<source.length())i++;continue;}
                    if(ch==quote){if(i<source.length()&&source.charAt(i)==quote)i++;else{ended=true;break;}}
                }
                if(!ended)return null;code.append(" ? ");continue;
            }
            code.append(c);i++;
        }
        return code.toString();
    }
    private static void add(List<Unit> result,String source,int start,int end) { if(hasContent(source.substring(start,end))) result.add(new Unit(source,start,end)); }
    private static boolean hasContent(String s) {return !withoutLeadingComments(s).trim().isEmpty();}
    public static String withoutLeadingComments(String text) {
        String s=text.trim();
        while(true) {
            if(s.startsWith("--")||s.startsWith("#")) {int end=s.indexOf('\n');s=end<0?"":s.substring(end+1).trim();}
            else if(s.startsWith("/*")&&!s.startsWith("/*!")) {int end=s.indexOf("*/",2);if(end<0)return s;s=s.substring(end+2).trim();}
            else return s;
        }
    }
    private static int line(String s,int offset) {int n=1;for(int i=0;i<Math.min(offset,s.length());i++)if(s.charAt(i)=='\n')n++;return n;}
    private static AppException error(String s,int offset,String message) {return new AppException(message+"（第 "+line(s,offset)+" 行）");}
}
