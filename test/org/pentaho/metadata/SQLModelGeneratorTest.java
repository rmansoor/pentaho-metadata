/*
 * Copyright 2009 Pentaho Corporation.  All rights reserved.
 * This software was developed by Pentaho Corporation and is provided under the terms
 * of the Mozilla Public License, Version 1.1, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to http://www.mozilla.org/MPL/MPL-1.1.txt. The Original Code is the Pentaho
 * BI Platform.  The Initial Developer is Pentaho Corporation.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */
package org.pentaho.metadata;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;


import org.apache.commons.lang.StringUtils;
import org.junit.Assert;
import org.junit.Test;
import org.pentaho.di.core.Props;
import org.pentaho.metadata.model.Category;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.LogicalColumn;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.query.model.CombinationType;
import org.pentaho.metadata.query.model.Constraint;
import org.pentaho.metadata.query.model.Order;
import org.pentaho.metadata.query.model.Query;
import org.pentaho.metadata.query.model.Selection;
import org.pentaho.metadata.query.model.util.QueryXmlHelper;
import org.pentaho.metadata.repository.InMemoryMetadataDomainRepository;
import org.pentaho.metadata.util.SQLModelGenerator;
import org.pentaho.metadata.util.SQLModelGeneratorException;
import org.pentaho.metadata.util.SerializationService;
import org.pentaho.metadata.util.ThinModelConverter;
import org.pentaho.pms.mql.MQLQueryImpl;
import org.pentaho.pms.schema.BusinessCategory;
import org.pentaho.pms.schema.BusinessColumn;
import org.pentaho.pms.schema.BusinessModel;
import org.pentaho.pms.schema.SchemaMeta;
import org.pentaho.pms.schema.concept.types.datatype.DataTypeSettings;
import org.pentaho.pms.util.Settings;

public class SQLModelGeneratorTest {
  
  @Test
  public void testSQLModelGenerator() {
    // basic tests
    try {
    SerializationService service = new SerializationService();
    
    String xml = service.serializeDomain(generateModel());
  
    System.out.println(xml);
    
    Domain domain2 = service.deserializeDomain(xml);
    
    Assert.assertEquals(1, domain2.getPhysicalModels().size());
    } catch(SQLModelGeneratorException smge) {
      Assert.fail();
    }
  }
  
  @Test
  public void testToLegacy() {
    if(!Props.isInitialized()) {
      Props.init(Props.TYPE_PROPERTIES_EMPTY);
    }
    Domain domain = null;
    SchemaMeta meta = null;
    try {
      domain = generateModel();
      meta = ThinModelConverter.convertToLegacy(domain);
    } catch (Exception e){
      e.printStackTrace();
      Assert.fail();
    }
    
    String locale = Locale.getDefault().toString();
    
    // verify conversion worked.
    BusinessModel model = meta.findModel("MODEL_1");
    Assert.assertNotNull(model);
    String local = model.getName(locale);
    Assert.assertEquals("newdatasource", model.getName(locale));
    BusinessCategory cat = model.getRootCategory().findBusinessCategory(Settings.getBusinessCategoryIDPrefix()+ "newdatasource");
    Assert.assertNotNull(cat);
    Assert.assertEquals("newdatasource", cat.getName(locale));
    
    Assert.assertEquals(1, cat.getBusinessColumns().size());
    
    // this tests the inheritance of physical cols made it through
    BusinessColumn col = cat.getBusinessColumn(0);
    Assert.assertEquals("CUSTOMERNAME", col.getName(locale));
    Assert.assertNotNull(col.getBusinessTable());
    Assert.assertEquals("LOGICAL_TABLE_1", col.getBusinessTable().getId());

    Assert.assertEquals(col.getDataType(), DataTypeSettings.STRING);
    Assert.assertEquals("select customername from customers where customernumber < 171", col.getBusinessTable().getTargetTable());
    Assert.assertEquals("select customername from customers where customernumber < 171", col.getPhysicalColumn().getTable().getTargetTable());
    Assert.assertEquals("CUSTOMERNAME", col.getPhysicalColumn().getFormula());
    Assert.assertEquals(false, col.getPhysicalColumn().isExact());
    
  }

  @Test
  public void testQueryXmlSerialization() {
    try {
      Domain domain = generateModel();
      LogicalModel model = domain.findLogicalModel("MODEL_1");
      Query query = new Query(domain, model);
      
      Category category = model.findCategory(Settings.getBusinessCategoryIDPrefix()+ "newdatasource");
      LogicalColumn column = category.findLogicalColumn("bc_CUSTOMERNAME");
      query.getSelections().add(new Selection(category, column, null));
      
      query.getConstraints().add(new Constraint(CombinationType.AND, "[CATEGORY.bc_CUSTOMERNAME] = \"bob\""));
  
      query.getOrders().add(new Order(new Selection(category, column, null), Order.Type.ASC));
      
      QueryXmlHelper helper = new QueryXmlHelper();
      String xml = helper.toXML(query);
      
      InMemoryMetadataDomainRepository repo = new InMemoryMetadataDomainRepository();
      try {
        repo.storeDomain(domain, true);
      } catch (Exception e) {
        e.printStackTrace();
        Assert.fail();
      }
      Query newQuery = null;
        newQuery = helper.fromXML(repo, xml);
      // verify that when we serialize and deserialize, the xml stays the same. 
      Assert.assertEquals(xml, helper.toXML(newQuery));
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail();
    }

  }
  
  @Test
  public void testQueryConversion() throws Exception {
    Domain domain = generateModel();
    LogicalModel model = domain.findLogicalModel("MODEL_1");
    Query query = new Query(domain, model);
    
    Category category = model.findCategory(Settings.getBusinessCategoryIDPrefix()+ "newdatasource");
    LogicalColumn column = category.findLogicalColumn("bc_CUSTOMERNAME");
    query.getSelections().add(new Selection(category, column, null));
    
    query.getConstraints().add(new Constraint(CombinationType.AND, "[bc_newdatasource.bc_CUSTOMERNAME] = \"bob\""));

    query.getOrders().add(new Order(new Selection(category, column, null), Order.Type.ASC));
    MQLQueryImpl impl = null;
    try {
      impl = ThinModelConverter.convertToLegacy(query, null);
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail();
    }
    Assert.assertNotNull(impl);
    Assert.assertEquals(
        
        "SELECT DISTINCT \n" + 
        "          LOGICAL_TABLE_1.CUSTOMERNAME AS COL0\n" + 
        "FROM \n" + 
        "          (select customername from customers where customernumber < 171) LOGICAL_TABLE_1\n" + 
        "WHERE \n" + 
        "        (\n" + 
        "          (\n" + 
        "              LOGICAL_TABLE_1.CUSTOMERNAME  = 'bob'\n" + 
        "          )\n" + 
        "        )\n" + 
        "ORDER BY \n" + 
        "          COL0\n",
        
        impl.getQuery().getQuery()
    );

  }
  
  private Connection getDataSourceConnection(String driverClass, String name, String username, String password, String url) throws Exception {
    Connection conn = null;

    if (StringUtils.isEmpty(driverClass)) {
      throw new Exception("Connection attempt failed"); //$NON-NLS-1$  
    }
    Class<?> driverC = null;

    try {
      driverC = Class.forName(driverClass);
    } catch (ClassNotFoundException e) {
      throw new Exception("Driver not found in the class path. Driver was " + driverClass, e); //$NON-NLS-1$
    }
    if (!Driver.class.isAssignableFrom(driverC)) {
      throw new Exception("Driver not found in the class path. Driver was " + driverClass); //$NON-NLS-1$    }
    }
    Driver driver = null;
    
    try {
      driver = driverC.asSubclass(Driver.class).newInstance();
    } catch (InstantiationException e) {
      throw new Exception("Unable to instance the driver", e); //$NON-NLS-1$
    } catch (IllegalAccessException e) {
      throw new Exception("Unable to instance the driver", e); //$NON-NLS-1$    }
    }
    try {
      DriverManager.registerDriver(driver);
      conn = DriverManager.getConnection(url, username, password);
      return conn;
    } catch (SQLException e) {
      throw new Exception("Unable to connect", e); //$NON-NLS-1$
    }
  }
  
  private Domain generateModel() throws SQLModelGeneratorException{
    String query = "select customername from customers where customernumber < 171";
    Connection connection = null;
    try {
    connection = getDataSourceConnection("org.hsqldb.jdbcDriver","SampleData"
        ,"pentaho_user", "password"
          ,"jdbc:hsqldb:file:test/solution/system/data/sampledata");
    } catch(Exception e) {
      e.printStackTrace();
      Assert.fail();
    }
    SQLModelGenerator generator = new SQLModelGenerator("newdatasource", "SampleData", connection, query);
    return generator.generate(); 
  }
  
}