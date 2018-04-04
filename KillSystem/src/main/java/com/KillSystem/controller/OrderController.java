package com.KillSystem.controller;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.KillSystem.Service.GoodsService;
import com.KillSystem.Service.OrderService;
import com.KillSystem.common.ServerResponse;
import com.KillSystem.domain.Order;
import com.KillSystem.util.MD5Util;
import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.demo.trade.config.Configs;
import com.google.common.collect.Maps;



/**
 * @author xcxcxcxcx
 * 
 * 
 * 2018年4月4日
 *
 */
@Controller
public class OrderController {
	
	private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
	
	@Autowired
	private OrderService orderService;
	
	@Autowired
	private GoodsService goodsService;
	
	private boolean hasPay = false;
	
	/**
	 * 计时器:创建订单、减少库存后，需要在6分钟之内完成==>预支付==>支付成功，
	 * 		若无法完成后续支付流程，删除订单、撤销库存减少
	 * 目的:防止过多的无效的、长期不支付订单占据秒杀库存量
	 * 										
	 */		
	class MyTask extends TimerTask{

		private Order order;
		
		MyTask(Order order){
			this.order = order;
		}
		
	    @Override
	    public void run() {
	    	if(!hasPay) {
	    		goodsService.incrGoodsStock(order);
	    		System.out.println("超时后撤回库存减一成功！");
	    		if(orderService.deleteOrderInRedis(order) == 0) {
	    			System.out.println("超时后删除订单失败！");
	    		}
	    		if(orderService.deletePayInRedis(order) == 0) {
	    			System.out.println("超时后删除支付订单失败！");
	    		}
	    	}else {
	    		if(orderService.getPayState(order) == "0") {
	    			goodsService.incrGoodsStock(order);
		    		System.out.println("超时后撤回库存减一成功！");
		    		if(orderService.deleteOrderInRedis(order) == 0) {
		    			System.out.println("超时后删除订单失败！");
		    		}
		    		if(orderService.deletePayInRedis(order) == 0) {
		    			System.out.println("超时后删除支付订单失败！");
		    		}
	    		}
	    	}
	    }
	    
	}
	
	@RequestMapping("/createOrder.do")
	@ResponseBody
	public ServerResponse createOrder(Order order,HttpServletRequest request,HttpSession session) {
//		if (session.getAttribute("tel_num") == null && session.getAttribute("passwd") == null) {
//			return ServerResponse.createByErrorMessage("请先登陆！");
//		}
		//构造不重复order_id
		String order_id = MD5Util.MD5EncodeUtf8(order.getTel_num()+order.getGoods_id()+DateTime.now().toString());
		order.setOrder_id(order_id);
		if(orderService.orderIsExist(order)) {
			System.out.println("订单已经存在！");
			return ServerResponse.createByErrorMessage("订单已经存在！");
		}
		
		try {
			
			//1.在redis中创建订单，若失败，返回“创建订单失败”
			if(orderService.createOrderInRedis(order) == 0) {
				return ServerResponse.createByErrorMessage("订单已经存在！");
			}
			
			//2.在redis中库存减一，若失败，撤回创建订单操作并返回“创建订单失败”
			if(goodsService.decrGoodsStock(order) < 0) {
				if(orderService.deleteOrderInRedis(order)==0) {
					System.out.println("撤回创建订单失败：订单不存在");
				}

				return ServerResponse.createByErrorMessage("库存减一失败！");
			}
			
		} catch (Exception e) {
			// TODO: handle exception
			logger.error("createOrder操作超时!", e);
			System.out.println("创建订单失败！");
			return ServerResponse.createByErrorMessage("创建订单失败！");
		}
		System.out.println("创建订单成功！");
		//创建定时器对象
        Timer t=new Timer();
        //在3秒后执行MyTask类中的run方法
        t.schedule(new MyTask(order), 360000);
		return ServerResponse.createBySuccess("创建订单成功！", order);
		
	}
	
	@RequestMapping("/alipay.do")
	public ModelAndView alipay(Order order) {
		return new ModelAndView("alipay","order",order);
	}
	
	@RequestMapping("/pay.do")
	@ResponseBody
	public ServerResponse pay(Order order,HttpServletRequest request,HttpSession session) {
//		if (session.getAttribute("tel_num") == null && session.getAttribute("passwd") == null) {
//			return ServerResponse.createByErrorMessage("请先登陆！");
//		}
		hasPay = true;
		//todo
		//1.在redis中创建支付订单，若失败，返回“订单已存在”
		if(orderService.createPayInRedis(order)==0) {
			return ServerResponse.createByErrorMessage(order.getOrder_id() + "_pay订单已存在");
		}
		//2.收到支付宝回调后，在redis中更新支付订单，若失败，返回“订单支付失败,取消支付并删除订单和删除订单支付”
		String path = request.getSession().getServletContext().getRealPath("upload");
        return orderService.pay(order,path);
	
	}
	
	@RequestMapping("/alipayCallback.do")
	@ResponseBody
	public Object alipayCallback(HttpServletRequest request) {
		//todo
		Map<String,String> params = Maps.newHashMap();

        Map requestParams = request.getParameterMap();
        for(Iterator iter = requestParams.keySet().iterator();iter.hasNext();){
            String name = (String)iter.next();
            String[] values = (String[]) requestParams.get(name);
            String valueStr = "";
            for(int i = 0 ; i <values.length;i++){

                valueStr = (i == values.length -1)?valueStr + values[i]:valueStr + values[i]+",";
            }
            params.put(name,valueStr);
        }
        logger.info("支付宝回调,sign:{},trade_status:{},参数:{}",params.get("sign"),params.get("trade_status"),params.toString());

        //非常重要,验证回调的正确性,是不是支付宝发的.并且呢还要避免重复通知.

        params.remove("sign_type");
        try {
            boolean alipayRSACheckedV2 = AlipaySignature.rsaCheckV2(params, Configs.getAlipayPublicKey(),"utf-8",Configs.getSignType());

            if(!alipayRSACheckedV2){
                return ServerResponse.createByErrorMessage("非法请求,验证不通过,再恶意请求我就报警找网警了");
            }
        } catch (AlipayApiException e) {
            logger.error("支付宝验证回调异常",e);
        }

        //todo 验证各种数据
        

        ServerResponse serverResponse = orderService.aliCallback(params);
        if(serverResponse.isSuccess()){
            return "success";
        }
        return "failed";
		
	}
	
	@RequestMapping("/payIsSuccess.do")
	@ResponseBody
	public ServerResponse payIsSuccess(Order order,HttpServletRequest request,HttpSession session) {
		//查询该订单是否支付成功：在redis中查询key{订单号+pay}的value值是否为1，若为1，返回支付成功，若为0，返回未支付。
		String flag = orderService.getPayState(order);
		if(flag == null) {
			return ServerResponse.createByErrorMessage("该支付订单不存在!");
		}
		boolean data = ("0".equals(flag) ? false : true);
		return ServerResponse.createBySuccess("订单状态:data",data);

	}
	
	
}