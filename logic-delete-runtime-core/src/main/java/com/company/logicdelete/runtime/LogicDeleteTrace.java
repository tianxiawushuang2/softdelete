package com.company.logicdelete.runtime;
import java.util.UUID;import java.util.function.Supplier;
public final class LogicDeleteTrace {private LogicDeleteTrace(){}public static void runWithEntry(String type,String name,Runnable task){callWithEntry(type,name,()->{task.run();return null;});}public static <T>T callWithEntry(String type,String name,Supplier<T> task){try{EntryContext.set(type,name,UUID.randomUUID().toString().replace("-",""));return task.get();}finally{EntryContext.clear();}}}
