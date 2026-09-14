/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { ResultString } from '../models/ResultString';
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class Service {
    /**
     * 应用健康检查
     * @returns ResultString OK
     * @throws ApiError
     */
    public static healthCheck(): CancelablePromise<ResultString> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/health',
        });
    }
}
