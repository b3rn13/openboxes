/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.api

import grails.converters.JSON
import org.codehaus.groovy.grails.web.json.JSONObject
import org.pih.warehouse.inventory.InventoryItem
import org.pih.warehouse.inventory.StockMovementService
import org.pih.warehouse.product.Product
import org.pih.warehouse.requisition.RequisitionStatus

import java.text.DateFormat
import java.text.SimpleDateFormat

class StockMovementApiController {

    StockMovementService stockMovementService

    def list = {
        int max = Math.min(params.max ? params.int('max') : 10, 1000)
        int offset = params.offset? params.int("offset") : 0
        def stockMovements = stockMovementService.getStockMovements(max, offset)
        stockMovements = stockMovements.collect { StockMovement stockMovement ->
            Map json = stockMovement.toJson()
            def excludes = params.list("exclude")
            if (excludes) {
                excludes.each { exclude ->
                    json.remove(exclude)
                }
            }
            return json
        }
        render ([data:stockMovements] as JSON)
    }

    def read = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id, params.stepNumber)
        render ([data:stockMovement] as JSON)
    }

    def create = { StockMovement stockMovement ->
        stockMovement = stockMovementService.createStockMovement(stockMovement)
        response.status = 201
        render ([data:stockMovement] as JSON)
	}

    def update = { //StockMovement stockMovement ->

        JSONObject jsonObject = request.JSON
        log.info "json: " + jsonObject

        // Bind all other properties to stock movement
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        if (!stockMovement) {
            stockMovement = new StockMovement()
        }

        // Remove attributes that cause issues in the default grails data binder
        List lineItems = jsonObject.remove("lineItems")
        DateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy")
        String dateRequested = jsonObject.remove("dateRequested")
        String dateShipped = jsonObject.remove("dateShipped")

        // Dates aren't bound properly using default JSON binding
        if (dateShipped) stockMovement.dateShipped = dateFormat.parse(dateShipped)
        if (dateRequested) stockMovement.dateRequested = dateFormat.parse(dateRequested)

        // Bind the rest of the JSON attributes to the stock movement object
        bindData(stockMovement, jsonObject)

        // Bind all line items
        if (lineItems) {
            // Need to clear the existing line items so we only process the modified ones
            stockMovement.lineItems.clear()
            bindLineItems(stockMovement, lineItems)
        }

        // Create or update stock movement
        stockMovementService.updateStockMovement(stockMovement)

        forward(action: "read")
    }

    def delete = {
        stockMovementService.deleteStockMovement(params.id)
        render status: 204
    }


    def status = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        render ([data:stockMovement?.status] as JSON)
    }

    def deleteStatus = {
        stockMovementService.rollbackStockMovement(params.id)
        forward(action: "read")
    }

    /**
     * Peforms a status update on the stock movement and forwards to the read action.
     */
    def updateStatus = {
        JSONObject jsonObject = request.JSON
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)

        Boolean statusOnly =
                jsonObject.containsKey("statusOnly") ? jsonObject.getBoolean("statusOnly") : false

        Boolean clearPicklist =
                jsonObject.containsKey("clearPicklist") ? jsonObject.getBoolean("clearPicklist") : false

        Boolean createPicklist =
                jsonObject.containsKey("createPicklist") ? jsonObject.getBoolean("createPicklist") : false

        RequisitionStatus status =
                jsonObject.containsKey("status") ? jsonObject.status as RequisitionStatus : null

        Boolean rollback =
                jsonObject.containsKey("rollback") ? jsonObject.getBoolean("rollback") : false

        if (status && statusOnly) {
            stockMovementService.updateStatus(params.id, status)
        }
        else {
            if (rollback) {
                stockMovementService.rollbackStockMovement(params.id)
            }

            if (status) {
                switch (status) {
                    case RequisitionStatus.CREATED:
                        break;
                    case RequisitionStatus.EDITING:
                        break;
                    case RequisitionStatus.VERIFYING:
                        break;
                    case RequisitionStatus.PICKING:
                        if (clearPicklist) stockMovementService.clearPicklist(stockMovement)
                        if (createPicklist) stockMovementService.createPicklist(stockMovement)
                        break;
                    case RequisitionStatus.PICKED:
                        break;
                    case RequisitionStatus.ISSUED:
                        stockMovementService.sendStockMovement(params.id)
                        break;
                    default:
                        throw new IllegalArgumentException("Cannot update status with invalid status ${jsonObject.status}")
                        break;

                }
                // If the dependent actions were updated properly then we can update the
                stockMovementService.updateStatus(params.id, status)
            }
        }
        forward(action: "read")
    }

    /**
     * Bind the given line items (JSONArray) to StockMovementItem objects and add them to the given
     * StockMovement object.
     *
     * NOTE: THis method was necessary because the default data binder for Grails command objects
     * does not seem to handle nested objects very well.
     *
     * FIXME Refactor data binding
     *
     * @param stockMovement
     * @param lineItems
     */
    void bindLineItems(StockMovement stockMovement, List lineItems) {
        log.info "line items: " + lineItems
        lineItems.each { lineItem ->
            StockMovementItem stockMovementItem = new StockMovementItem()
            stockMovementItem.id = lineItem.id
            stockMovementItem.product = lineItem["product.id"] ? Product.load(lineItem["product.id"]) : null
            stockMovementItem.inventoryItem = lineItem["inventoryItem.id"] ? InventoryItem.load(lineItem["inventoryItem.id"]) : null
            stockMovementItem.quantityRequested = lineItem.quantityRequested ? new BigDecimal(lineItem.quantityRequested) : null
            stockMovementItem.sortOrder = lineItem.sortOrder && !lineItem.isNull("sortOrder") ? new Integer(lineItem.sortOrder) : null

            // Actions
            stockMovementItem.delete = lineItem.delete ? Boolean.parseBoolean(lineItem.delete):Boolean.FALSE
            stockMovementItem.revert = lineItem.revert ? Boolean.parseBoolean(lineItem.revert):Boolean.FALSE
            stockMovementItem.cancel = lineItem.cancel ? Boolean.parseBoolean(lineItem.cancel):Boolean.FALSE
            stockMovementItem.substitute = lineItem.substitute ? Boolean.parseBoolean(lineItem.substitute):Boolean.FALSE

            // When substituting a product, we need to include the new product, quantity and reason code
            stockMovementItem.newProduct = lineItem["newProduct.id"] ? Product.load(lineItem["newProduct.id"]) : null
            stockMovementItem.newQuantity = lineItem.newQuantity ? new BigDecimal(lineItem.newQuantity) : null

            // When revising quantity you need quantity revised and reason code
            stockMovementItem.quantityRevised = lineItem.quantityRevised ? new BigDecimal(lineItem.quantityRevised) : null
            stockMovementItem.reasonCode = lineItem.reasonCode
            stockMovementItem.comments = lineItem.comments

            // Not supported yet because recipient is a String on Requisition Item and a Person on Shipment Item.
            //stockMovementItem.recipient = lineItem["recipient.id"] ? Person.load(lineItem["recipient.id"]) : null

            stockMovement.lineItems.add(stockMovementItem)
        }
    }

}