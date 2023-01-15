;;;; Copyright © 2011 Paul Stadig
;;;;
;;;; Licensed under the Apache License, Version 2.0 (the "License"); you may not
;;;; use this file except in compliance with the License.  You may obtain a copy
;;;; of the License at
;;;;
;;;;   http://www.apache.org/licenses/LICENSE-2.0
;;;;
;;;; Unless required by applicable law or agreed to in writing, software
;;;; distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
;;;; WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
;;;; License for the specific language governing permissions and limitations
;;;; under the License.
(ns migratus.database
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [migratus.migration.sql :as sql-mig]
            [migratus.properties :as props]
            [migratus.protocols :as proto]
            [migratus.utils :as utils]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql])
  (:import java.io.File
           [java.sql Connection SQLException]
           [javax.sql DataSource]
           [java.util.jar JarEntry JarFile]))

(def default-migrations-table "schema_migrations")

(defn migration-table-name
  "Makes migration table name available from config."
  [config]
  (:migration-table-name config default-migrations-table))

(defn connection-or-spec
  "Migration code from java.jdbc to next.jdbc .
   java.jdbc accepts a spec that contains a ^java.sql.Connection as :connection.
   Return :connection or the db spec."
  [db]
  (let [conn (:connection db)]
    (if conn conn db)))

(def reserved-id -1)

(defn mark-reserved [db table-name]
  (boolean
    (try
      (sql/insert! (connection-or-spec db) table-name  {:id reserved-id} {:return-keys false})
      (catch Exception _e))))

(defn mark-unreserved [db table-name]
  (sql/delete! (connection-or-spec db) table-name ["id=?" reserved-id]))

(defn complete? [db table-name id]
  (first (sql/query (connection-or-spec db)
                    [(str "SELECT * from " table-name " WHERE id=?") id])))

(defn mark-complete [db table-name description id]
  (log/debug "marking" id "complete")
  (sql/insert! (connection-or-spec db)
               table-name {:id          id
                           :applied     (java.sql.Timestamp. (.getTime (java.util.Date.)))
                           :description description}))

(defn mark-not-complete [db table-name id]
  (log/debug "marking" id "not complete")
  (sql/delete! (connection-or-spec db) table-name ["id=?" id]))

(defn migrate-up* [db {:keys [tx-handles-ddl?] :as config} {:keys [name] :as migration}]
  (let [id         (proto/id migration)
        table-name (migration-table-name config)]
    (if (mark-reserved db table-name)
      (try
        (when-not (complete? db table-name id)
          (proto/up migration (assoc config :conn db))
          (mark-complete db table-name name id)
          :success)
        (catch Throwable up-e
          (if tx-handles-ddl?
            (log/error (format "Migration %s failed because %s" name (.getMessage up-e)))
            (do
              (log/error (format "Migration %s failed because %s backing out" name (.getMessage up-e)))
              (try
                (proto/down migration (assoc config :conn db))
                (catch Throwable down-e
                  (log/debug down-e (format "As expected, one of the statements failed in %s while backing out the migration" name))))))
          (throw up-e))
        (finally
          (mark-unreserved db table-name)))
      :ignore)))

(defn migrate-down* [db config migration]
  (let [id         (proto/id migration)
        table-name (migration-table-name config)]
    (if (mark-reserved db table-name)
      (try
        (when (complete? db table-name id)
          (proto/down migration (assoc config :conn db))
          (mark-not-complete db table-name id)
          :success)
        (finally
          (mark-unreserved db table-name)))
      :ignore)))

(defn find-init-script-file [migration-dir init-script-name]
  (first
    (filter (fn [^File f] (and (.isFile f) (= (.getName f) init-script-name)))
            (file-seq migration-dir))))

(defn find-init-script-resource [migration-dir ^JarFile jar init-script-name]
  (let [init-script-path (utils/normalize-path
                           (.getPath (io/file migration-dir init-script-name)))]
    (->> (.entries jar)
         (enumeration-seq)
         (filter (fn [^JarEntry entry]
                   (.endsWith (.getName entry) init-script-path)))
         (first)
         (.getInputStream jar))))

(defn find-init-script [dir init-script-name]
  (let [dir (utils/ensure-trailing-slash dir)]
    (when-let [migration-dir (utils/find-migration-dir dir)]
      (if (instance? File migration-dir)
        (find-init-script-file migration-dir init-script-name)
        (find-init-script-resource dir migration-dir init-script-name)))))

(defn connection-from-datasource [ds]
  (try (.getConnection ^DataSource ds)
       (catch Exception e
         (log/error e (str "Error getting DB connection from source" ds))
         (throw e))))

(defn connect*
  "Connects to the store - SQL database in this case.
   Accepts a ^java.sql.Connection, ^javax.sql.DataSource or a db spec."
  [db]
  (assert (map? db) "db must be a map")
  (let [^Connection conn
        (cond
          (:connection db) (let [c (:connection db)]
                             (assert (instance? Connection c) "c is not a Connection")
                             c)
          (:datasource db) (let [ds (:datasource db)]
                             (assert (instance? DataSource ds) "ds is not a DataSource")
                             (connection-from-datasource ds))
          :else (try
                  ;; @ieugen: We can set auto-commit here as next.jdbc supports it.
                  ;; But I guess we need to conside the case when we get a Connection directly
                  (jdbc/get-connection db)
                  (catch Exception e
                    (log/error e (str "Error creating DB connection for "
                                      (utils/censor-password db)))
                    (throw e))))]
    ;; Mutate Connection: set autocommit to false is necessary for transactional mode
    ;; and must be enabled for non transactional mode
    (if (:transaction? db)
      (.setAutoCommit conn false)
      (.setAutoCommit conn true))
    {:connection conn}))

(defn disconnect* [db]
  (when-let [^Connection conn (:connection db)]
    (when-not (.isClosed conn)
      (.close conn))))

(defn completed-ids* [db table-name]
  (let [t-con (connection-or-spec db)]
    (->> (sql/query t-con
                    [(str "select id, applied from " table-name " where id != " reserved-id)]
                    {:builder-fn rs/as-unqualified-lower-maps})
         (sort-by :applied #(compare %2 %1))
         (map :id)
         (doall))))

(defn table-exists?
  "Checks whether the migrations table exists, by attempting to select from
  it. Note that this appears to be the only truly portable way to determine
  whether the table exists in a schema which the `db` configuration will find
  via a `SELECT FROM` or `INSERT INTO` the table. (In particular, note that
  attempting to find the table in the database meta-data as exposed by the JDBC
  driver does *not* tell you whether the table is on the current schema search
  path.)"
  [db table-name]
  (try
    ;; TODO: @ieugen: do we need :rollback-only here ?
    (let [db (connection-or-spec db)]
      (sql/query db [(str "SELECT 1 FROM " table-name)]))
    true
    (catch SQLException _
      false)))

(defn migration-table-up-to-date?
  [db table-name]
  (jdbc/with-transaction [t-con (connection-or-spec db)]
    (try
      (sql/query t-con [(str "SELECT applied,description FROM " table-name)])
      true
      (catch SQLException _
        false))))

(defn datetime-backend?
  "Checks whether the underlying backend requires the applied column to be
  of type datetime instead of timestamp."
  [db]
  (let [^Connection conn (:connection db)
        db-name          (.. conn getMetaData getDatabaseProductName)]
    ;; TODO: @ieugen: we could leverage honeysql here but it might be a heavy extra dependency?!
    (if (= "Microsoft SQL Server" db-name)
      "DATETIME"
      "TIMESTAMP")))

(defn create-migration-table!
  "Creates the schema for the migration table via t-con in db in table-name"
  [db modify-sql-fn table-name]
  (log/info "creating migration table" (str "'" table-name "'"))
  (let [timestamp-column-type (datetime-backend? db)]
    (jdbc/with-transaction [t-con (connection-or-spec db)]
      (jdbc/execute!
       t-con
       (modify-sql-fn
        (str "CREATE TABLE " table-name
             " (id BIGINT NOT NULL PRIMARY KEY, applied " timestamp-column-type
             ", description VARCHAR(1024) )"))))))

(defn update-migration-table!
  "Updates the schema for the migration table via t-con in db in table-name"
  [db modify-sql-fn table-name]
  (log/info "updating migration table" (str "'" table-name "'"))
  (jdbc/with-transaction [t-con (connection-or-spec db)]
    (jdbc/execute-batch!
     t-con
     [(modify-sql-fn
       [(str "ALTER TABLE " table-name " ADD COLUMN description varchar(1024)")
        (str "ALTER TABLE " table-name " ADD COLUMN applied timestamp")])])))


(defn init-schema! [db table-name modify-sql-fn]
  ;; Note: the table-exists? *has* to be done in its own top-level
  ;; transaction. It can't be run in the same transaction as other code, because
  ;; if the table doesn't exist, then the error it raises internally in
  ;; detecting that will (on Postgres, at least) mark the transaction as
  ;; rollback only. That is, the act of detecting that it is necessary to create
  ;; the table renders the current transaction unusable for that purpose. I
  ;; blame Heisenberg.
  (or (table-exists? db table-name)
      (create-migration-table! db modify-sql-fn table-name))
  (or (migration-table-up-to-date? db table-name)
      (update-migration-table! db modify-sql-fn table-name)))

(defn run-init-script! [init-script-name init-script conn modify-sql-fn transaction?]
  (try
    (log/info "running initialization script '" init-script-name "'")
    (log/trace "\n" init-script "\n")
    ;; TODO: @ieugen Why was db-do-prepared used here ?
    ;; Do we need to care about `transaction?` in next.jdbc ?
    (if transaction?
      (jdbc/execute! conn (modify-sql-fn init-script))
      (jdbc/execute! conn (modify-sql-fn init-script) {}))
    (catch Throwable t
      (log/error t "failed to initialize the database with:\n" init-script "\n")
      (throw t))))

(defn inject-properties [init-script properties]
  (if properties
    (props/inject-properties properties init-script)
    init-script))

(defn init-db! [db migration-dir init-script-name modify-sql-fn transaction? properties]
  (if-let [init-script (some-> (find-init-script migration-dir init-script-name)
                               slurp
                               (inject-properties properties))]
    (if transaction?
      (jdbc/with-transaction [t-con (connection-or-spec db)]
        (run-init-script! init-script-name init-script t-con modify-sql-fn transaction?))
      (run-init-script! init-script-name init-script (connection-or-spec db) modify-sql-fn transaction?))
    (log/error "could not locate the initialization script '" init-script-name "'")))

(defrecord Database [connection config]
  proto/Store
  (config [this] config)
  (init [this]
    (let [conn (connect* (assoc (:db config) :transaction? (:init-in-transaction? config)))]
      (try
        (init-db! conn
                  (utils/get-migration-dir config)
                  (utils/get-init-script config)
                  (sql-mig/wrap-modify-sql-fn (:modify-sql-fn config))
                  (get config :init-in-transaction? true)
                  (props/load-properties config))
        (finally
          (disconnect* conn)))))
  (completed-ids [this]
    (completed-ids* @connection (migration-table-name config)))
  (migrate-up [this migration]
              (log/info "Connection is " @connection
                        "Config is" (update config :db utils/censor-password))
              (if (proto/tx? migration :up)
                (jdbc/with-transaction [t-con (connection-or-spec @connection)]
                  (migrate-up* t-con config migration))
                (migrate-up* (:db config) config migration)))
  (migrate-down [this migration]
                (log/info "Connection is " @connection
                          "Config is" (update config :db utils/censor-password))
                (if (proto/tx? migration :down)
                  (jdbc/with-transaction [t-con (connection-or-spec @connection)]
                    (migrate-down* t-con config migration))
                  (migrate-down* (:db config) config migration)))
  (connect [this]
    (reset! connection (connect* (:db config)))
    (init-schema! @connection
                  (migration-table-name config)
                  (sql-mig/wrap-modify-sql-fn (:modify-sql-fn config))))
  (disconnect [this]
    (disconnect* @connection)
    (reset! connection nil)))

(defmethod proto/make-store :database
  [config]
  (->Database (atom nil) config))
