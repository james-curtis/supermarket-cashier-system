package com.exam.core.base.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.PageReadListener;
import com.exam.core.base.service.BaseService;
import com.exam.core.common.metadata.IPage;
import com.exam.core.common.metadata.PathInfo;
import com.exam.core.common.plugin.pagination.Page;
import com.exam.core.common.util.GenericsUtils;
import com.exam.core.common.util.UrlUtil;
import com.exam.supermarket.constant.FileTypeConstant;
import com.exam.supermarket.metadata.FieldDescriptor;
import com.exam.supermarket.util.AuthorActionDispatcherUtil;
import com.exam.supermarket.vo.ToastVo;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.beanutils.BeanUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class BaseController<T> extends HttpServlet {

    @Getter
    @Setter
    private String[] noNeedLogin = {"*"};

    @Getter
    @Setter
    private String[] noNeedRight = {};

    @Getter
    @Setter
    private BaseService<T> service;

    @Getter
    @Setter
    private String templatePath;

    @Getter
    @Setter
    private PathInfo pathInfo;

    private Logger logger = Logger.getLogger(this.getClass().getName());

    protected void dispatch(HttpServletRequest req, HttpServletResponse resp) {
        this.pathInfo = UrlUtil.parsePathInfo(req.getRequestURI());
        this.templatePath = String.format("/WEB-INF/templates/%s", this.pathInfo.getController());
        req.setAttribute("pathInfo", this.pathInfo);
        AuthorActionDispatcherUtil.actionDispatcher(this, req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        dispatch(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        this.doGet(req, resp);
    }

    protected void autoForward(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher(String.format("%s/%s.jsp", this.templatePath, this.pathInfo.getAction()))
            .forward(req, resp);
    }

    protected void autoForward(HttpServletRequest req, HttpServletResponse resp, String tpl)
        throws ServletException, IOException {
        req.getRequestDispatcher(String.format("%s/%s.jsp", this.templatePath, tpl))
            .forward(req, resp);
    }

    protected void index(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        int page = 1;
        int size = 10;
        try {
            page = req.getParameter("page") == null ? 1 : Integer.valueOf(req.getParameter("page"));
            size = req.getParameter("size") == null ? 20 : Integer.valueOf(req.getParameter("size"));
        } catch (NumberFormatException e) {
            resp.sendRedirect(req.getServletPath());
            return;
        }

        IPage<T> pagination = new Page<>(page, Math.min(10, size));
        this.service.page(pagination);
        req.setAttribute("pagination", pagination);
        req.setAttribute("records", pagination.getRecords());
        req.setAttribute("pageQuery", String.format("page=%d&size=%d", page, size));
        req.setAttribute("fields", this.getIndexFields(req, resp));
        this.autoForward(req, resp);
    }

    protected void add(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        req.setAttribute("fields", getAddFields(req, resp));
        this.autoForward(req, resp);
    }

    protected void edit(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        T po = this.service.getById(req.getParameter("id"));
        Map<String, String> record = null;
        try {
            record = BeanUtils.describe(po);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        req.setAttribute("record", record);
        req.setAttribute("fields", getUpdateFields(req, resp));
        this.autoForward(req, resp);
    }

    protected void forwardHome(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.sendRedirect(String.format("/%s", this.pathInfo.getController()));
    }

    protected void exportXls(HttpServletRequest req, HttpServletResponse response) throws IOException {
        // 这里注意 有同学反应使用swagger 会导致各种问题，请直接用浏览器或者用postman
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        // 这里URLEncoder.encode可以防止中文乱码 当然和easyexcel没有关系
        String fileName = URLEncoder.encode(
                String.format("导出%s数据", this.service.getDao().getTable()),
                StandardCharsets.UTF_8).
            replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        EasyExcel.write(response.getOutputStream(),
                GenericsUtils.getSuperClassGenericType(this.getClass()))
            .sheet("Sheet1")
            .doWrite(() -> this.service.list());
    }

    protected void importXls(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Part file = req.getPart("file");
        if (file == null) {
            req.setAttribute("toast", new ToastVo("错误", "上传文件失败"));
            this.forwardHome(req, resp);
            return;
        } else if (!FileTypeConstant.xlsx.equals(file.getContentType())) {
            req.setAttribute("toast", new ToastVo("错误", "文件类型错误"));
            this.forwardHome(req, resp);
            return;
        }

        AtomicInteger xlsxLen = new AtomicInteger();
        // 这里默认每次会读取100条数据 然后返回过来 直接调用使用数据就行
        // 具体需要返回多少行可以在`PageReadListener`的构造函数设置
        EasyExcel.read(file.getInputStream(), GenericsUtils.getSuperClassGenericType(this.getClass()),
            new PageReadListener<T>(dataList -> {
                xlsxLen.addAndGet(dataList.size());
                logger.info(String.format("读取到%s条数据", dataList.size()));
                this.service.saveBatch(dataList);
            })).sheet().doRead();
        req.setAttribute("infoMsg", String.format("成功导入%d条数据", xlsxLen.get()));
        req.getRequestDispatcher("/WEB-INF/templates/common/info.jsp").forward(req, resp);
    }

    protected void delete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        this.service.removeById(req.getParameter("id"));
        this.forwardHome(req, resp);
    }

    protected void save(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Class<T> poClass = GenericsUtils.getSuperClassGenericType(this.getClass());
        T po = null;
        try {
            po = poClass.getConstructor().newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        try {
            BeanUtils.populate(po, req.getParameterMap());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        this.service.saveOrUpdate(po);
        this.forwardHome(req, resp);
    }

    protected Map<String, FieldDescriptor> getUpdateFields(HttpServletRequest req, HttpServletResponse resp) {
        return new LinkedHashMap<>();
    }

    protected Map<String, FieldDescriptor> getAddFields(HttpServletRequest req, HttpServletResponse resp) {
        return new LinkedHashMap<>();
    }

    protected Map<String, FieldDescriptor> getIndexFields(HttpServletRequest req, HttpServletResponse resp) {
        return new LinkedHashMap<>();
    }

}
