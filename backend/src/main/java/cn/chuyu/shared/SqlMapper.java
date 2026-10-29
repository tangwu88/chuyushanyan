package cn.chuyu.shared;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SqlMapper {
  class Provider {
    public static String query(Map<String, Object> args) {
      return args.get("sql").toString();
    }
  }

  @SelectProvider(type = Provider.class, method = "query")
  List<Map<String, Object>> query(@Param("sql") String sql, @Param("p") Map<String, Object> params);

  @UpdateProvider(type = Provider.class, method = "query")
  int execute(@Param("sql") String sql, @Param("p") Map<String, Object> params);
}
