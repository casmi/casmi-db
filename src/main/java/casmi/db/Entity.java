/*
 *   casmi
 *   http://casmi.github.com/
 *   Copyright (C) 2011, Xcoo, Inc.
 *
 *  casmi is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package casmi.db;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Blob;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import casmi.db.annotation.Fieldname;
import casmi.db.annotation.Ignore;
import casmi.db.annotation.PrimaryKey;
import casmi.db.annotation.Tablename;

/**
 * An entity class expressing a table in a database.
 *
 * <p>
 * This class is used for an Active Record function in casmi.
 * Defines an original class that extends this Entity class.
 * </p>
 *
 * <p>
 * Example:
 * <pre><code>
 *     import casmi.sql.Entity;
 *     import casmi.sql.annotation.Fieldname;
 *     import casmi.sql.annotation.Ignore;
 *
 *     public class MyEntity extends Entity {
 *
 *         public  String text;
 *
 *         {@code @Fieldname}(NUM_1)
 *         private int    num1;
 *
 *         public  double num2;
 *
 *         {@code @Ignore}
 *         public  float  num3;
 *
 *         public int getNum1() {
 *             return num1;
 *         }
 *
 *         public void setNum1(int num1) {
 *             this.num1 = num1;
 *         }
 *     }
 * </code></pre>
 * </p>
 *
 * @see casmi.db.SQLite
 * @see casmi.db.MySQL
 * @see casmi.db.Query
 * @see casmi.sql.annotation.Fieldname
 * @see casmi.sql.annotation.Ignore
 * @see casmi.sql.annotation.PrimaryKey
 * @see casmi.sql.annotation.Tablename
 *
 * @author T. Takeuchi
 */
abstract public class Entity {

    /** A table name. */
    String tablename;

    /** SQL instance. */
    SQL sql;

    private Class<? extends Entity> type;

    private boolean newEntity = true;

    boolean autoPrimaryKey = false;

    Column primaryKey;

    /** Columns. */
    Column[] columns;

    final <T extends Entity> void init(SQL sql, Class<T> type) {

        this.sql  = sql;
        this.type = type;

        tablename = getTablename(type);
        try {
            primaryKey = searchPrimaryKey();
            columns    = fieldsToColumns();
            if (!tableExists()) {
                createTable();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private final boolean tableExists() throws SQLException {

        DatabaseMetaData dmd = sql.getConnection().getMetaData();
        ResultSet rs = dmd.getTables(null, null, null, null);
        while (rs.next()) {
            if (tablename.equals(rs.getString("TABLE_NAME"))) {
                return true;
            }
        }
        return false;
    }

    private final void createTable() throws SQLException {
        String stmt = StatementGenerator.createTable(this);
        sql.execute(stmt);
    }

    public final void save() throws SQLException {

        primaryKey = searchPrimaryKey();
        columns    = fieldsToColumns();

        if (newEntity) {
            insert();
            newEntity = false;
        } else {
            update();
        }
    }

    private final void insert() throws SQLException {

        String sqlStr = StatementGenerator.insert(sql.getSQLType());

        sqlStr = sqlStr.replaceAll(":table", tablename);

        StringBuilder sb1 = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        List<Object> list = new ArrayList<Object>();

        if (!autoPrimaryKey) {
            sb1.append(primaryKey.getField());
            sb2.append('?');
            list.add(primaryKey.getValue());
        }

        for (Column c : columns) {
            if (sb1.length() != 0) sb1.append(',');
            if (sb2.length() != 0) sb2.append(',');
            sb1.append(c.getField());
            sb2.append("?");
            list.add(c.getValue());
        }
        sqlStr = sqlStr.replaceAll(":fields", sb1.toString());
        sqlStr = sqlStr.replaceAll(":values", sb2.toString());

        sql.execute(sqlStr, list.toArray());
    }

    private final void update() throws SQLException {

        String sqlStr = StatementGenerator.update(sql.getSQLType());

        sqlStr = sqlStr.replaceAll(":table", tablename);

        StringBuilder sb = new StringBuilder();
        List<Object> list = new ArrayList<Object>();

        for (Column column : columns) {
            if (sb.length() != 0) sb.append(',');
            sb.append(column.getField());
            sb.append("=?");
            list.add(column.getValue());
        }

        list.add(primaryKey.getValue());

        sqlStr = sqlStr.replaceAll(":sets", sb.toString());

        sqlStr = sqlStr.replaceAll(":key", primaryKey.getField());
        sqlStr = sqlStr.replaceAll(":key_value", "?");

        sql.execute(sqlStr, list.toArray());
    }

    public final void delete() throws SQLException {

        if (newEntity) {
            throw new SQLException("This record has not been in a database yet.");
        }

        String where = primaryKey.getField() + "=" + primaryKey.getValue();
        String stmt = StatementGenerator.delete(sql.getSQLType(), tablename, where);
        sql.execute(stmt);
    }

    private final Column searchPrimaryKey() {
        Column  c    = null;
        boolean flag = false;

        for (Field f : type.getDeclaredFields()) {
            try {
                String   name  = f.getName();
                String   field;
                Class<?> type  = f.getType();

                // "this$0" is a tacit field generated automatically if the class
                // is an inner class.
                // It expresses a declared class object, so should be ignored.
                if (name.equals("this$0")) continue;

                if (f.getAnnotation(Ignore.class)     != null) continue;
                if (f.getAnnotation(PrimaryKey.class) == null) continue;

                flag = true;

                Fieldname FieldnameAnnot = f.getAnnotation(Fieldname.class);
                if (FieldnameAnnot != null) {
                    field = FieldnameAnnot.value();
                } else {
                    field = name;
                }

                if (Modifier.isPublic(f.getModifiers())) {
                    // access directly
                    Object value = f.get(this);
                    if (value == null) {
                        c = new Column(name, field, null, type);
                    } else if (type.equals(int.class) ||
                               type.equals(Integer.class)) {
                        c = new Column(name, field, (Integer)value, type);
                    } else if (type.equals(short.class) ||
                               type.equals(Short.class)) {
                        c = new Column(name, field, (Short)value, type);
                    } else if (type.equals(long.class) ||
                               type.equals(Long.class)) {
                        c = new Column(name, field, (Long)value, type);
                    } else if (type.equals(String.class)) {
                        c = new Column(name, field, (String)value, type);
                    } else if (type.equals(double.class) ||
                               type.equals(Double.class)) {
                        c = new Column(name, field, (Double)value, type);
                    } else if (type.equals(float.class) ||
                               type.equals(Float.class)) {
                        c = new Column(name, field, (Float)value, type);
                    } else if (type.equals(Date.class)) {
                        c = new Column(name, field, (Date)value, type);
                    } else if (type.equals(Blob.class)) {
                        c = new Column(name, field, (Blob)value, type);
                    } else {
                        continue;
                    }
                } else {
                    // access using setter/getter
                    PropertyDescriptor pd = new PropertyDescriptor(name, this.type);
                    Method m = pd.getReadMethod();
                    Object value = m.invoke(this, (Object[])null);
                    if (value == null) {
                        c = new Column(name, field, null, type);
                    } else if (type.equals(int.class) ||
                               type.equals(Integer.class)) {
                        c = new Column(name, field, (Integer)value, type);
                    } else if (type.equals(short.class) ||
                               type.equals(Short.class)) {
                        c = new Column(name, field, (Short)value, type);
                    } else if (type.equals(long.class) ||
                               type.equals(Long.class)) {
                        c = new Column(name, field, (Long)value, type);
                    } else if (type.equals(String.class)) {
                        c = new Column(name, field, (String)value, type);
                    } else if (type.equals(double.class) ||
                               type.equals(Double.class)) {
                        c = new Column(name, field, (Double)value, type);
                    } else if (type.equals(float.class) ||
                               type.equals(Float.class)) {
                        c = new Column(name, field, (Float)value, type);
                    } else if (type.equals(Date.class)) {
                        c = new Column(name, field, (Date)value, type);
                    } else if (type.equals(Blob.class)) {
                        c = new Column(name, field, (Blob)value, type);
                    } else {
                        continue;
                    }
                }
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (IntrospectionException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }

            break;
        }

        if (!flag) {
            c = new Column("id", "id", -1, int.class);
            autoPrimaryKey = true;
        }

        return c;
    }

    private final Column[] fieldsToColumns() throws SQLException {

        List<Column> list = new ArrayList<Column>();

        for (Field f : type.getDeclaredFields()) {
            Column c = null;

            try {
                String   name  = f.getName();
                String   field;
                Class<?> type  = f.getType();

                // "this$0" is a tacit field generated automatically if the class
                // is an inner class.
                // It expresses a declared class object, so should be ignored.
                if (name.equals("this$0")) continue;

                if (f.getAnnotation(Ignore.class)     != null) continue;
                if (f.getAnnotation(PrimaryKey.class) != null) continue;

                Fieldname FieldnameAnnot = f.getAnnotation(Fieldname.class);
                if (FieldnameAnnot != null) {
                    field = FieldnameAnnot.value();
                } else {
                    field = name;
                }

                if (Modifier.isPublic(f.getModifiers())) {
                    // access directly
                    Object value = f.get(this);
                    if (value == null) {
                        c = new Column(name, field, null, type);
                    } else if (type.equals(int.class) ||
                               type.equals(Integer.class)) {
                        c = new Column(name, field, (Integer)value, type);
                    } else if (type.equals(short.class) ||
                               type.equals(Short.class)) {
                        c = new Column(name, field, (Short)value, type);
                    } else if (type.equals(long.class) ||
                               type.equals(Long.class)) {
                        c = new Column(name, field, (Long)value, type);
                    } else if (type.equals(String.class)) {
                        c = new Column(name, field, (String)value, type);
                    } else if (type.equals(double.class) ||
                               type.equals(Double.class)) {
                        c = new Column(name, field, (Double)value, type);
                    } else if (type.equals(float.class) ||
                               type.equals(Float.class)) {
                        c = new Column(name, field, (Float)value, type);
                    } else if (type.equals(Date.class)) {
                        c = new Column(name, field, (Date)value, type);
                    } else if (type.equals(Blob.class)) {
                        c = new Column(name, field, (Blob)value, type);
                    } else {
                        continue;
                    }
                } else {
                    // access using setter/getter
                    PropertyDescriptor pd = new PropertyDescriptor(name, this.type);
                    Method m = pd.getReadMethod();
                    Object value = m.invoke(this, (Object[])null);
                    if (value == null) {
                        c = new Column(name, field, null, type);
                    } else if (type.equals(int.class) ||
                               type.equals(Integer.class)) {
                        c = new Column(name, field, (Integer)value, type);
                    } else if (type.equals(short.class) ||
                               type.equals(Short.class)) {
                        c = new Column(name, field, (Short)value, type);
                    } else if (type.equals(long.class) ||
                               type.equals(Long.class)) {
                        c = new Column(name, field, (Long)value, type);
                    } else if (type.equals(String.class)) {
                        c = new Column(name, field, (String)value, type);
                    } else if (type.equals(double.class) ||
                               type.equals(Double.class)) {
                        c = new Column(name, field, (Double)value, type);
                    } else if (type.equals(float.class) ||
                               type.equals(Float.class)) {
                        c = new Column(name, field, (Float)value, type);
                    } else if (type.equals(Date.class)) {
                        c = new Column(name, field, (Date)value, type);
                    } else if (type.equals(Blob.class)) {
                        c = new Column(name, field, (Blob)value, type);
                    } else {
                        continue;
                    }
                }

                list.add(c);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (IntrospectionException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }

        if (list.isEmpty()) {
            throw new SQLException("Fields are not validated.");
        }

        return list.toArray(new Column[list.size()]);
    }

    private final void columnsToFields() {
        Field f;

        // primary key
        if (!autoPrimaryKey) {
            try {
                f = this.type.getDeclaredField(primaryKey.getName());

                if (Modifier.isPublic(f.getModifiers())) {
                    f.set(this, primaryKey.getValue());
                } else {
                    PropertyDescriptor pd = new PropertyDescriptor(f.getName(), this.type);
                    Method m = pd.getWriteMethod();
                    m.invoke(this, primaryKey.getValue());
                }
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (IntrospectionException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }

        // other fields
        for (Column c : columns) {
            try {
                f = this.type.getDeclaredField(c.getName());

                // "this$0" is a tacit field generated automatically if the class
                // is an inner class.
                // It expresses a declared class object, so should be ignored.
                if (f.getName().equals("this$0")) continue;

                if (Modifier.isPublic(f.getModifiers())) {
                    f.set(this, c.getValue());
                } else {
                    PropertyDescriptor pd = new PropertyDescriptor(f.getName(), this.type);
                    Method m = pd.getWriteMethod();
                    m.invoke(this, c.getValue());
                }
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (IntrospectionException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    final void setValuesFromResultSet(ResultSet resultSet) throws SQLException {
        Object value;

        value = sql.get(resultSet, primaryKey.getType(), primaryKey.getField());
        primaryKey.setValue(value);
        newEntity = false;

        for (Column column : columns) {
            value = sql.get(resultSet, column.getType(), column.getField());
            column.setValue(value);
        }

        columnsToFields();
    }

    final void setValuesFromReslutSet(ResultSet resultSet, String... fields)
        throws SQLException {
        Object value;
        boolean flag;

        flag = false;
        for (String field : fields) {
            if (primaryKey.getField().equals(field)) flag = true;
        }
        if (flag) {
            value = sql.get(resultSet, primaryKey.getType(), primaryKey.getField());
            primaryKey.setValue(value);
            newEntity = false;
        }

        for (Column column : columns) {
            flag = false;
            for (String field : fields) {
                if (column.getField().equals(field)) flag = true;
            }

            if (flag) {
                value = sql.get(resultSet, column.getType(), column.getField());
                column.setValue(value);
            }
        }

        columnsToFields();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(tablename);
        sb.append(" {");
        sb.append(primaryKey.getField());
        sb.append("(key): ");
        sb.append(primaryKey.getValue());
        for (Column column : columns) {
            sb.append(", ");
            sb.append(column.getField());
            sb.append(": ");
            sb.append(column.getValue());
        }
        sb.append('}');

        return sb.toString();
    }

    static final <T extends Entity> String getTablename(Class<T> type) {
        Tablename annotation = type.getAnnotation(Tablename.class);
        if (annotation != null) {
            return annotation.value();
        }

        return type.getSimpleName();
    }

    public String getTablename() {
        return tablename;
    }

    static final <T extends Entity> boolean isAutoPrimaryKey(Class<T> type) {
        boolean autoPrimaryKey = true;

        for (Field f : type.getDeclaredFields()) {
            if (f.getAnnotation(Ignore.class)     == null &&
                f.getAnnotation(PrimaryKey.class) != null) {
                autoPrimaryKey = false;
                break;
            }
        }

        return autoPrimaryKey;
    }

    static final <T extends Entity> String getPrimaryKeyField(Class<T> type) {
        String field;

        for (Field f : type.getDeclaredFields()) {
            try {
                String name  = f.getName();

                // "this$0" is a tacit field generated automatically if the class
                // is an inner class.
                // It expresses a declared class object, so should be ignored.
                if (name.equals("this$0")) continue;

                if (f.getAnnotation(Ignore.class)     != null) continue;

                if (f.getAnnotation(PrimaryKey.class) != null) {
                    Fieldname FieldnameAnnot = f.getAnnotation(Fieldname.class);
                    if (FieldnameAnnot != null) {
                        field = FieldnameAnnot.value();
                    } else {
                        field = name;
                    }
                    return field;
                }
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        return "id";
    }
}
