// Copyright 2017 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.sql;

import static com.yahoo.bard.webservice.druid.model.DefaultQueryType.GROUP_BY;

import com.yahoo.bard.webservice.data.dimension.Dimension;
import com.yahoo.bard.webservice.druid.model.DefaultQueryType;
import com.yahoo.bard.webservice.druid.model.QueryType;
import com.yahoo.bard.webservice.druid.model.aggregation.Aggregation;
import com.yahoo.bard.webservice.druid.model.having.Having;
import com.yahoo.bard.webservice.druid.model.orderby.LimitSpec;
import com.yahoo.bard.webservice.druid.model.orderby.SortDirection;
import com.yahoo.bard.webservice.druid.model.query.DruidAggregationQuery;
import com.yahoo.bard.webservice.druid.model.query.DruidQuery;
import com.yahoo.bard.webservice.druid.model.query.GroupByQuery;
import com.yahoo.bard.webservice.sql.aggregation.DruidSqlAggregationConverter;
import com.yahoo.bard.webservice.sql.aggregation.SqlAggregation;
import com.yahoo.bard.webservice.sql.evaluator.FilterEvaluator;
import com.yahoo.bard.webservice.sql.evaluator.HavingEvaluator;
import com.yahoo.bard.webservice.sql.evaluator.PostAggregationEvaluator;
import com.yahoo.bard.webservice.sql.helper.CalciteHelper;
import com.yahoo.bard.webservice.sql.helper.SqlTimeConverter;
import com.yahoo.bard.webservice.table.SqlPhysicalTable;

import com.google.common.collect.ImmutableList;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.tools.RelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Default implementation of converting a {@link DruidQuery} into a sql query.
 */
public class DruidQueryToSqlConverter {
    private static final Logger LOG = LoggerFactory.getLogger(DruidQueryToSqlConverter.class);
    protected final CalciteHelper calciteHelper;
    private final SqlTimeConverter sqlTimeConverter;
    private final BiFunction<Aggregation, ApiToFieldMapper, Optional<SqlAggregation>> druidSqlAggregationConverter;
    private final HavingEvaluator havingEvaluator;
    private final FilterEvaluator filterEvaluator;
    private final PostAggregationEvaluator postAggregationEvaluator;
    public static final int NO_OFFSET = -1;
    public static final int NO_LIMIT = -1;

    /**
     * Constructs the default converter.
     *
     * TODO could make an interface with {@link #isValidQuery(DruidQuery)} and
     * {@link #buildSqlQuery(DruidAggregationQuery, ApiToFieldMapper)}. Maybe make it a generic
     * more generic like "{@code DruidQueryConverter<T>}"
     *
     * @param calciteHelper  The calcite helper for this database.
     */
    public DruidQueryToSqlConverter(CalciteHelper calciteHelper) {
        this.calciteHelper = calciteHelper;
        this.sqlTimeConverter = buildSqlTimeConverter();
        this.druidSqlAggregationConverter = buildDruidSqlTypeConverter();
        this.havingEvaluator = buildHavingEvaluator();
        this.filterEvaluator = buildFilterEvaluator();
        this.postAggregationEvaluator = buildPostAggregationEvaluator();
    }

    /**
     * Builds a HavingEvaluator.
     *
     * @return a having evaluator
     */
    protected HavingEvaluator buildHavingEvaluator() {
        return new HavingEvaluator();
    }

    /**
     * Builds a filterEvaluator.
     *
     * @return a filter evaluator
     */
    protected FilterEvaluator buildFilterEvaluator() {
        return new FilterEvaluator();
    }

    /**
     * Builds a time converter to designating how to translate between druid and sql
     * time information.
     *
     * @return a new time converter.
     */
    protected SqlTimeConverter buildSqlTimeConverter() {
        return new SqlTimeConverter();
    }

    /**
     * Builds a PostAggregationEvaluator.
     *
     * @return a post aggregation evaluator
     */
    protected PostAggregationEvaluator buildPostAggregationEvaluator() {
        return new PostAggregationEvaluator();
    }

    /**
     * Builds a converter between druid and sql aggregations.
     *
     * @return a new druid to sql aggregation converter.
     */
    protected BiFunction<Aggregation, ApiToFieldMapper, Optional<SqlAggregation>> buildDruidSqlTypeConverter() {
        return new DruidSqlAggregationConverter();
    }

    /**
     * Determines whether or not a query is able to be processed using
     * the Sql backend.
     *
     * @param druidQuery  The query to check if is able to be processed.
     *
     * @return true if a valid query, else false.
     */
    protected boolean isValidQuery(DruidQuery<?> druidQuery) {
        QueryType queryType = druidQuery.getQueryType();
        LOG.debug("Processing {} query\n {}", queryType, druidQuery);

        if (queryType instanceof DefaultQueryType) {
            DefaultQueryType defaultQueryType = (DefaultQueryType) queryType;
            switch (defaultQueryType) {
                case TIMESERIES:
                case GROUP_BY:
                    return true;
            }
        }

        return false;
    }

    /**
     * Builds the druid query as sql and returns it as a string.
     *
     * @param druidQuery  The query to convert to sql.
     * @param apiToFieldMapper  The mapping between api and physical names for the query.
     *
     * @return the sql equivalent of the query.
     */
    public String buildSqlQuery(DruidAggregationQuery<?> druidQuery, ApiToFieldMapper apiToFieldMapper) {
        SqlPhysicalTable sqlTable = (SqlPhysicalTable) druidQuery.getDataSource()
                .getPhysicalTable()
                .getSourceTable();

        LOG.debug(
                "Querying table {} with catalog: {}, schema {}, using timestampColumn {}",
                sqlTable.getName(),
                sqlTable.getCatalog(),
                sqlTable.getSchemaName(),
                sqlTable.getTimestampColumn()
        );

        RelNode query = convertDruidQueryToRelNode(druidQuery, apiToFieldMapper, sqlTable);
        RelToSqlConverter relToSql = calciteHelper.getNewRelToSqlConverter();
        SqlPrettyWriter sqlWriter = calciteHelper.getNewSqlWriter();

        return writeSql(sqlWriter, relToSql, query);
    }

    /**
     * Converts the druid query to a {@link RelNode}.
     * Additional project step compare to methid in base class.
     *
     * @param druidQuery  The query to convert to sql.
     * @param apiToFieldMapper  The mapping between api and physical names for the query.
     * @param sqlTable  The sql table being queried against.
     *
     * @return the sql equivalent of the query.
     */
    protected RelNode convertDruidQueryToRelNode(
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper,
            SqlPhysicalTable sqlTable
    ) {
        RelBuilder builder = calciteHelper.getNewRelBuilder(sqlTable.getSchemaName(), sqlTable.getCatalog());
        builder = builder.scan(sqlTable.getName());
        return builder
                .filter(
                        getAllWhereFilters(builder, druidQuery, apiToFieldMapper, sqlTable.getTimestampColumn())
                )
                .aggregate(
                        builder.groupKey(getAllGroupByColumns(
                                builder,
                                druidQuery,
                                apiToFieldMapper,
                                sqlTable.getTimestampColumn()
                        )),
                        getAllQueryAggregations(builder, druidQuery, apiToFieldMapper)
                )
                .project(
                        (Iterable) ImmutableList.builder()
                                .addAll(builder.fields())
                                .addAll(getPostAggregations(builder, druidQuery, apiToFieldMapper))
                                .build()
                )
                .filter(
                        getHavingFilter(builder, druidQuery, apiToFieldMapper)
                )
                .sortLimit(
                        NO_OFFSET,
                        getLimit(druidQuery),
                        getSort(builder, druidQuery, apiToFieldMapper, sqlTable.getTimestampColumn())
                )
                .build();
    }

    /**
     * Returns the post-aggregations of the query.
     *
     * @param builder the RelBuilder
     * @param druidQuery the source druid query
     * @param apiToFieldMapper api column to logic column name mapping
     * @return a list of RexNode representing the post aggregation
     */
    private List<RexNode> getPostAggregations(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper
    ) {
        List<RexNode> postAggregationFields = new ArrayList<>();
        druidQuery.getPostAggregations().stream()
                .map(postAggregation -> getPostAggregationEvaluator()
                        .evaluatePostAggregation(postAggregation, builder, apiToFieldMapper))
                .forEach(postAggregationFields::add);
        return postAggregationFields;
    }

    /**
     * Gets the number of rows to limit results to for a Group by Query. Otherwise no limit is applied.
     *
     * @param druidQuery  The query to get the row limit from.
     *
     * @return the number of rows to include in the results.
     */
    protected int getLimit(DruidAggregationQuery<?> druidQuery) {
        if (druidQuery.getQueryType().equals(GROUP_BY)) {
            GroupByQuery groupByQuery = (GroupByQuery) druidQuery;
            LimitSpec limitSpec = groupByQuery.getLimitSpec();
            if (limitSpec != null) {
                return limitSpec.getLimit().orElse(NO_LIMIT);
            }
        }
        return NO_LIMIT;
    }

    /**
     * Finds the sorting for a druid query.
     *
     * @param builder  The RelBuilder created with Calcite.
     * @param druidQuery  The query to find the sorting from.
     * @param apiToFieldMapper  The mapping from api to physical names.
     * @param timestampColumn  The name of the timestamp column in the database.
     *
     * @return a collection of rexnodes to apply sorts in calcite.
     */
    protected List<RexNode> getSort(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper,
            String timestampColumn
    ) {
        // druid does NULLS FIRST
        List<RexNode> sorts = new ArrayList<>();
        int timePartFunctions = sqlTimeConverter.timeGrainToDatePartFunctions(druidQuery.getGranularity()).size();
        int groupBys = druidQuery.getDimensions().size() + timePartFunctions;

        List<RexNode> limitSpecSorts = new ArrayList<>();
        Set<String> limitSpecColumns = new HashSet<>();
        if (druidQuery.getQueryType().equals(GROUP_BY)) {
            GroupByQuery groupByQuery = (GroupByQuery) druidQuery;
            LimitSpec limitSpec = groupByQuery.getLimitSpec();
            if (limitSpec != null) {
                limitSpec.getColumns()
                        .stream()
                        .map(orderByColumn -> {
                            String orderByField = apiToFieldMapper.apply(orderByColumn.getDimension());
                            limitSpecColumns.add(orderByField);
                            RexNode sort = builder.field(orderByField);
                            if (orderByColumn.getDirection().equals(SortDirection.DESC)) {
                                sort = builder.desc(sort);
                            }
                            return sort;
                        })
                        .forEach(limitSpecSorts::add);
            }
        }

        // add time group by
        if (timePartFunctions == 0) {
            sorts.add(builder.field(timestampColumn));
        }
        sorts.addAll(builder.fields().subList(druidQuery.getDimensions().size(), groupBys));

        // add limit spec group by
        sorts.addAll(limitSpecSorts);

        // add remaining group by
        List<RexNode> unorderedDimensions = druidQuery.getDimensions().stream()
                .map(Dimension::getApiName)
                .map(apiToFieldMapper)
                .filter(columnName -> !limitSpecColumns.contains(columnName))
                .map(builder::field)
                .collect(Collectors.toList());
        sorts.addAll(unorderedDimensions);

        return sorts.stream()
                .map(sort -> builder.call(SqlStdOperatorTable.NULLS_FIRST, sort))
                .collect(Collectors.toList());
    }

    /**
     * Gets all the dimensions from a druid query as fields for calcite.
     *
     * @param builder  The RelBuilder created with Calcite.
     * @param druidQuery  The query to find the having filter from.
     * @param apiToFieldMapper  The mapping from api to physical name.
     *
     * @return the list of dimensions as {@link RexNode} for Calcite's builder.
     */
    private List<RexNode> getDimensionFields(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper
    ) {
        return druidQuery.getDimensions().stream()
                .map(Dimension::getApiName)
                .map(apiToFieldMapper)
                .map(builder::field)
                .collect(Collectors.toList());
    }

    /**
     * Returns the RexNode used to filter the druidQuery.
     *
     * @param builder  The RelBuilder created with Calcite.
     * @param druidQuery  The query from which to find filter all the filters for.
     * @param apiToFieldMapper  The mapping from api to physical names.
     * @param timestampColumn  The name of the timestamp column in the database.
     *
     * @return the combined RexNodes that should be filtered on.
     */
    protected RexNode getAllWhereFilters(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper,
            String timestampColumn
    ) {
        RexNode timeFilter = sqlTimeConverter.buildTimeFilters(
                builder,
                druidQuery,
                timestampColumn
        );

        if (druidQuery.getFilter() != null) {
            RexNode druidQueryFilter = getFilterEvaluator().evaluateFilter(
                    druidQuery.getFilter(),
                    builder,
                    apiToFieldMapper
            );
            return builder.and(timeFilter, druidQueryFilter);
        }

        return timeFilter;
    }

    /**
     * Gets the collection of having filters to be applied from the druid query.
     *
     * @param builder  The RelBuilder created with Calcite.
     * @param druidQuery  The query to find the having filter from.
     * @param apiToFieldMapper  The mapping from api to physical name.
     *
     * @return the collection of equivalent filters for calcite.
     */
    protected Collection<RexNode> getHavingFilter(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper
    ) {
        RexNode filter = null;
        if (druidQuery.getQueryType().equals(GROUP_BY)) {
            Having having = ((GroupByQuery) druidQuery).getHaving();

            if (having != null) {
                filter = getHavingEvaluator().evaluateHaving(having, builder, apiToFieldMapper);
            }
        }

        return Collections.singletonList(filter);
    }

    /**
     * Find all druid aggregations and convert them to {@link org.apache.calcite.tools.RelBuilder.AggCall}.
     *
     * @param builder  The RelBuilder created with Calcite.
     * @param druidQuery  The druid query to get the aggregations of.
     * @param apiToFieldMapper  The mapping from api to physical name.
     *
     * @return the list of aggregations.
     */
    protected List<RelBuilder.AggCall> getAllQueryAggregations(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper
    ) {
        return druidQuery.getAggregations()
                .stream()
                .map(aggregation -> getDruidSqlAggregationConverter().apply(aggregation, apiToFieldMapper))
                .map(optionalSqlAggregation -> optionalSqlAggregation.orElseThrow(() -> {
                    String msg = "Couldn't build sql aggregation with " + optionalSqlAggregation;
                    LOG.debug(msg);
                    return new RuntimeException(msg);
                }))
                .map(sqlAggregation -> {
                    if (sqlAggregation.getSqlAggFunction() == SqlStdOperatorTable.COUNT) {
                        return builder.countStar(sqlAggregation.getSqlAggregationAsName());
                    }
                    return builder.aggregateCall(
                            sqlAggregation.getSqlAggFunction(),
                            false,
                            null,
                            sqlAggregation.getSqlAggregationAsName(),
                            builder.field(sqlAggregation.getSqlAggregationFieldName())
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Collects all the time columns and dimensions to be grouped on.
     *
     * @param builder  The RelBuilder created with Calcite.
     * @param druidQuery  The query to find grouping columns from.
     * @param apiToFieldMapper  The mapping from api to physical name.
     * @param timestampColumn  The name of the timestamp column in the database.
     *
     * @return all columns which should be grouped on.
     */
    protected List<RexNode> getAllGroupByColumns(
            RelBuilder builder,
            DruidAggregationQuery<?> druidQuery,
            ApiToFieldMapper apiToFieldMapper,
            String timestampColumn
    ) {
        List<RexNode> timeFilters = sqlTimeConverter.buildGroupBy(
                builder,
                druidQuery.getGranularity(),
                timestampColumn
        );

        List<RexNode> dimensionFields = getDimensionFields(builder, druidQuery, apiToFieldMapper);

        List<RexNode> allGroupBys = new ArrayList<>(timeFilters.size() + dimensionFields.size());
        allGroupBys.addAll(timeFilters);
        allGroupBys.addAll(dimensionFields);
        return allGroupBys;
    }

    /**
     * Converts a RelBuilder into a sql string.
     *
     * @param sqlWriter  The writer to be used when translating the {@link org.apache.calcite.rel.RelNode} to sql.
     * @param relToSql  The converter from {@link org.apache.calcite.rel.RelNode} to
     * {@link org.apache.calcite.sql.SqlNode}.
     * @param query  The RelNode representing the query.
     *
     * @return the sql string built by the RelBuilder.
     */
    protected String writeSql(SqlPrettyWriter sqlWriter, RelToSqlConverter relToSql, RelNode query) {
        sqlWriter.reset();
        SqlSelect select = relToSql.visitChild(0, query).asSelect();
        return sqlWriter.format(select);
    }

    public SqlTimeConverter getTimeConverter() {
        return sqlTimeConverter;
    }

    protected BiFunction<Aggregation, ApiToFieldMapper, Optional<SqlAggregation>> getDruidSqlAggregationConverter() {
        return druidSqlAggregationConverter;
    }

    protected HavingEvaluator getHavingEvaluator() {
        return havingEvaluator;
    }

    protected FilterEvaluator getFilterEvaluator() {
        return filterEvaluator;
    }

    protected PostAggregationEvaluator getPostAggregationEvaluator() {
        return postAggregationEvaluator;
    }
}
