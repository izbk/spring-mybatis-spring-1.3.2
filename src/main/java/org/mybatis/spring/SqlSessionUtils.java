/**
 *    Copyright 2010-2016 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.spring;

import static org.springframework.util.Assert.notNull;

import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Handles MyBatis SqlSession life cycle. It can register and get SqlSessions from
 * Spring {@code TransactionSynchronizationManager}. Also works if no transaction is active.
 *
 * @author Hunter Presnall 
 * @author Eduardo Macarron
 */
public final class SqlSessionUtils {

  private static final Log LOGGER = LogFactory.getLog(SqlSessionUtils.class);

  private static final String NO_EXECUTOR_TYPE_SPECIFIED = "No ExecutorType specified";
  private static final String NO_SQL_SESSION_FACTORY_SPECIFIED = "No SqlSessionFactory specified";
  private static final String NO_SQL_SESSION_SPECIFIED = "No SqlSession specified";

  /**
   * This class can't be instantiated, exposes static utility methods only.
   */
  private SqlSessionUtils() {
    // do nothing
  }

  /**
   * Creates a new MyBatis {@code SqlSession} from the {@code SqlSessionFactory}
   * provided as a parameter and using its {@code DataSource} and {@code ExecutorType}
   *
   * @param sessionFactory a MyBatis {@code SqlSessionFactory} to create new sessions
   * @return a MyBatis {@code SqlSession}
   * @throws TransientDataAccessResourceException if a transaction is active and the
   *             {@code SqlSessionFactory} is not using a {@code SpringManagedTransactionFactory}
   */
  public static SqlSession getSqlSession(SqlSessionFactory sessionFactory) {
    ExecutorType executorType = sessionFactory.getConfiguration().getDefaultExecutorType();
    return getSqlSession(sessionFactory, executorType, null);
  }

  /**
   * Gets an SqlSession from Spring Transaction Manager or creates a new one if needed.
   * Tries to get a SqlSession out of current transaction. If there is not any, it creates a new one.
   * Then, it synchronizes the SqlSession with the transaction if Spring TX is active and
   * <code>SpringManagedTransactionFactory</code> is configured as a transaction manager.
   *
   * @param sessionFactory a MyBatis {@code SqlSessionFactory} to create new sessions
   * @param executorType The executor type of the SqlSession to create
   * @param exceptionTranslator Optional. Translates SqlSession.commit() exceptions to Spring exceptions.
   * @throws TransientDataAccessResourceException if a transaction is active and the
   *             {@code SqlSessionFactory} is not using a {@code SpringManagedTransactionFactory}
   * @see SpringManagedTransactionFactory
   */
  public static SqlSession getSqlSession(SqlSessionFactory sessionFactory, ExecutorType executorType, PersistenceExceptionTranslator exceptionTranslator) {

    notNull(sessionFactory, NO_SQL_SESSION_FACTORY_SPECIFIED);
    notNull(executorType, NO_EXECUTOR_TYPE_SPECIFIED);
    // 根据sqlSessionFactory从当前线程对应的资源map中获取SqlSessionHolder，当sqlSessionFactory创建了sqlSession，
    // 就会在事务管理器中添加一对映射：key为sqlSessionFactory，value为SqlSessionHolder，该类保存sqlSession及执行方式
    SqlSessionHolder holder = (SqlSessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);

    SqlSession session = sessionHolder(executorType, holder);
    //holder中存在则返回sqlSession
    if (session != null) {
      return session;
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Creating a new SqlSession");
    }
    //如果找不到，则根据执行类型构造一个新的sqlSession
    session = sessionFactory.openSession(executorType);
   // 注册sqlSession到SessionHolder
    registerSessionHolder(sessionFactory, executorType, exceptionTranslator, session);

    return session;
  }

  /**
   * Register session holder if synchronization is active (i.e. a Spring TX is active).
   *
   * Note: The DataSource used by the Environment should be synchronized with the
   * transaction either through DataSourceTxMgr or another tx synchronization.
   * Further assume that if an exception is thrown, whatever started the transaction will
   * handle closing / rolling back the Connection associated with the SqlSession.
   * 
   * @param sessionFactory sqlSessionFactory used for registration.
   * @param executorType executorType used for registration.
   * @param exceptionTranslator persistenceExceptionTranslater used for registration.
   * @param session sqlSession used for registration.
   */
  private static void registerSessionHolder(SqlSessionFactory sessionFactory, ExecutorType executorType,
      PersistenceExceptionTranslator exceptionTranslator, SqlSession session) {
    SqlSessionHolder holder;
    // 判断同步是否激活，只要SpringTX被激活，就是true
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      //加载环境变量，判断注册的事务管理器是否是SpringManagedTransaction，也就是Spring管理事务
      Environment environment = sessionFactory.getConfiguration().getEnvironment();

      if (environment.getTransactionFactory() instanceof SpringManagedTransactionFactory) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Registering transaction synchronization for SqlSession [" + session + "]");
        }
        // 如果是，则将sqlSession加载进事务管理的本地线程缓存中
        holder = new SqlSessionHolder(session, executorType, exceptionTranslator);
        //  以sessionFactory为key，holder为value，加入到TransactionSynchronizationManager管理的本地缓存
        // ThreadLocal<Map<Object, Object>> resources中
        TransactionSynchronizationManager.bindResource(sessionFactory, holder);
        // 将holder, sessionFactory的同步加入本地线程缓存中ThreadLocal<Set<TransactionSynchronization>> synchronizations
        TransactionSynchronizationManager.registerSynchronization(new SqlSessionSynchronization(holder, sessionFactory));
        // 设置当前holder和当前事务同步
        holder.setSynchronizedWithTransaction(true);
        // 增加引用数
        holder.requested();
      } else {
        if (TransactionSynchronizationManager.getResource(environment.getDataSource()) == null) {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("SqlSession [" + session + "] was not registered for synchronization because DataSource is not transactional");
          }
        } else {
          throw new TransientDataAccessResourceException(
              "SqlSessionFactory must be using a SpringManagedTransactionFactory in order to use Spring transaction synchronization");
        }
      }
    } else {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("SqlSession [" + session + "] was not registered for synchronization because synchronization is not active");
      }
    }
}

  private static SqlSession sessionHolder(ExecutorType executorType, SqlSessionHolder holder) {
    SqlSession session = null;
    // 如果holder不为空，且和当前事务同步
    if (holder != null && holder.isSynchronizedWithTransaction()) {
      // holder保存的执行类型和获取SqlSession的执行类型不一致，就会抛出异常，也就是说在同一个事务中，执行类型不能变化，
      // 原因就是同一个事务中同一个sqlSessionFactory创建的sqlSession会被重用
      if (holder.getExecutorType() != executorType) {
        throw new TransientDataAccessResourceException("Cannot change the ExecutorType when there is an existing transaction");
      }
      // 增加该holder,同一事务中同一个sqlSessionFactory创建的唯一sqlSession，其引用数增加，被使用的次数增加
      holder.requested();

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Fetched SqlSession [" + holder.getSqlSession() + "] from current transaction");
      }

      session = holder.getSqlSession();
    }
    return session;
  }

  /**
   * Checks if {@code SqlSession} passed as an argument is managed by Spring {@code TransactionSynchronizationManager}
   * If it is not, it closes it, otherwise it just updates the reference counter and
   * lets Spring call the close callback when the managed transaction ends
   *
   * @param session
   * @param sessionFactory
   */
  public static void closeSqlSession(SqlSession session, SqlSessionFactory sessionFactory) {
    notNull(session, NO_SQL_SESSION_SPECIFIED);
    notNull(sessionFactory, NO_SQL_SESSION_FACTORY_SPECIFIED);

    SqlSessionHolder holder = (SqlSessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);
    // 判断session是否被Spring事务管理，如果管理就会得到holder
    if ((holder != null) && (holder.getSqlSession() == session)) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Releasing transactional SqlSession [" + session + "]");
      }
      // 这里释放的作用，不是关闭，只是减少一下引用数，因为后面可能会被复用
      holder.released();
    } else {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Closing non transactional SqlSession [" + session + "]");
      }
      // 如果不是被spring管理，那么就不会被Spring去关闭回收，就需要自己close
      session.close();
    }
  }

  /**
   * Returns if the {@code SqlSession} passed as an argument is being managed by Spring
   *
   * @param session a MyBatis SqlSession to check
   * @param sessionFactory the SqlSessionFactory which the SqlSession was built with
   * @return true if session is transactional, otherwise false
   */
  public static boolean isSqlSessionTransactional(SqlSession session, SqlSessionFactory sessionFactory) {
    notNull(session, NO_SQL_SESSION_SPECIFIED);
    notNull(sessionFactory, NO_SQL_SESSION_FACTORY_SPECIFIED);

    SqlSessionHolder holder = (SqlSessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);

    return (holder != null) && (holder.getSqlSession() == session);
  }

  /**
   * Callback for cleaning up resources. It cleans TransactionSynchronizationManager and
   * also commits and closes the {@code SqlSession}.
   * It assumes that {@code Connection} life cycle will be managed by
   * {@code DataSourceTransactionManager} or {@code JtaTransactionManager}
   */
  private static final class SqlSessionSynchronization extends TransactionSynchronizationAdapter {

    private final SqlSessionHolder holder;

    private final SqlSessionFactory sessionFactory;

    private boolean holderActive = true;

    public SqlSessionSynchronization(SqlSessionHolder holder, SqlSessionFactory sessionFactory) {
      notNull(holder, "Parameter 'holder' must be not null");
      notNull(sessionFactory, "Parameter 'sessionFactory' must be not null");

      this.holder = holder;
      this.sessionFactory = sessionFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
      // order right before any Connection synchronization
      return DataSourceUtils.CONNECTION_SYNCHRONIZATION_ORDER - 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void suspend() {
      if (this.holderActive) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Transaction synchronization suspending SqlSession [" + this.holder.getSqlSession() + "]");
        }
        TransactionSynchronizationManager.unbindResource(this.sessionFactory);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() {
      if (this.holderActive) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Transaction synchronization resuming SqlSession [" + this.holder.getSqlSession() + "]");
        }
        TransactionSynchronizationManager.bindResource(this.sessionFactory, this.holder);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeCommit(boolean readOnly) {
      // Connection commit or rollback will be handled by ConnectionSynchronization or
      // DataSourceTransactionManager.
      // But, do cleanup the SqlSession / Executor, including flushing BATCH statements so
      // they are actually executed.
      // SpringManagedTransaction will no-op the commit over the jdbc connection
      // TODO This updates 2nd level caches but the tx may be rolledback later on! 
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        try {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Transaction synchronization committing SqlSession [" + this.holder.getSqlSession() + "]");
          }
          this.holder.getSqlSession().commit();
        } catch (PersistenceException p) {
          if (this.holder.getPersistenceExceptionTranslator() != null) {
            DataAccessException translated = this.holder
                .getPersistenceExceptionTranslator()
                .translateExceptionIfPossible(p);
            if (translated != null) {
              throw translated;
            }
          }
          throw p;
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeCompletion() {
      // Issue #18 Close SqlSession and deregister it now
      // because afterCompletion may be called from a different thread
      if (!this.holder.isOpen()) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Transaction synchronization deregistering SqlSession [" + this.holder.getSqlSession() + "]");
        }
        TransactionSynchronizationManager.unbindResource(sessionFactory);
        this.holderActive = false;
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Transaction synchronization closing SqlSession [" + this.holder.getSqlSession() + "]");
        }
        this.holder.getSqlSession().close();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterCompletion(int status) {
      if (this.holderActive) {
        // afterCompletion may have been called from a different thread
        // so avoid failing if there is nothing in this one
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Transaction synchronization deregistering SqlSession [" + this.holder.getSqlSession() + "]");
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(sessionFactory);
        this.holderActive = false;
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Transaction synchronization closing SqlSession [" + this.holder.getSqlSession() + "]");
        }
        this.holder.getSqlSession().close();
      }
      this.holder.reset();
    }
  }

}
